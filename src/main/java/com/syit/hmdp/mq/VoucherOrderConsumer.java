package com.syit.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.syit.hmdp.entity.VoucherOrder;
import com.syit.hmdp.service.IVoucherOrderService;
import com.syit.hmdp.utils.MqConstants;
import com.syit.hmdp.utils.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.syit.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
@RocketMQMessageListener(
    topic = MqConstants.VOUCHER_ORDER_TOPIC,
    consumerGroup = MqConstants.VOUCHER_ORDER_CONSUMER_GROUP,
    consumeMode = ConsumeMode.ORDERLY,
    messageModel = MessageModel.CLUSTERING
)
public class VoucherOrderConsumer implements RocketMQListener<String> {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> claimScript;
    private DefaultRedisScript<Long> rollbackScript;

    @PostConstruct
    public void init() {
        claimScript = new DefaultRedisScript<>();
        claimScript.setLocation(new ClassPathResource("seckill-order-claim.lua"));
        claimScript.setResultType(Long.class);

        rollbackScript = new DefaultRedisScript<>();
        rollbackScript.setLocation(new ClassPathResource("seckill-order-rollback.lua"));
        rollbackScript.setResultType(Long.class);
    }

    @Override
    public void onMessage(String messageBody) {
        VoucherOrderMessage msg;
        try {
            msg = JSONUtil.toBean(messageBody, VoucherOrderMessage.class);
        } catch (Exception e) {
            log.error("消息解析失败: {}", messageBody, e);
            return;
        }

        if (MqConstants.TAG_DELAY_CHECK.equals(msg.getTag())) {
            handleDelayCheck(msg);
        } else {
            handleOrder(msg);
        }
    }

    private void handleOrder(VoucherOrderMessage msg) {
        Long orderId   = msg.getOrderId();
        Long voucherId = msg.getVoucherId();
        Long userId    = msg.getUserId();

        // 1. Lua 幂等认领
        Long claimed = stringRedisTemplate.execute(
            claimScript, List.of(), String.valueOf(orderId));
        if (claimed == null || claimed != 1L) {
            log.info("订单已被认领或无效, 直接ACK, orderId={}", orderId);
            return;
        }

        // 2. 构建订单实体
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1);  // DB 业务状态: 1=未支付
        voucherOrder.setCreateTime(LocalDateTime.now());

        // 3. 执行 create（通过接口代理确保 @Transactional 生效）
        long start = System.currentTimeMillis();
        try {
            voucherOrderService.create(voucherOrder);

            // 4. 成功 → 已完成
            stringRedisTemplate.opsForValue().set(
                SECKILL_V2_ORDER_STATUS_KEY + orderId,
                OrderStatus.SUCCESS,
                1800, TimeUnit.SECONDS);
            stringRedisTemplate.delete(SECKILL_V2_ORDER_RETRY_KEY + orderId);

            long cost = System.currentTimeMillis() - start;
            log.info("订单创建成功, orderId={}, cost={}ms", orderId, cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("订单创建失败, orderId={}, cost={}ms", orderId, cost, e);

            // 5. 检查重试次数
            String retryKey = SECKILL_V2_ORDER_RETRY_KEY + orderId;
            Long retryCount = stringRedisTemplate.opsForValue().increment(retryKey);
            stringRedisTemplate.expire(retryKey, 1800, TimeUnit.SECONDS);

            if (retryCount != null && retryCount < OrderStatus.MAX_RETRY_COUNT) {
                stringRedisTemplate.opsForValue().set(
                    SECKILL_V2_ORDER_STATUS_KEY + orderId,
                    OrderStatus.FAIL_RETRYING,
                    1800, TimeUnit.SECONDS);
                log.info("订单置为 重试中, orderId={}, retryCount={}", orderId, retryCount);
                throw new RuntimeException("订单创建失败，等待MQ重投", e);
            } else {
                stringRedisTemplate.opsForValue().set(
                    SECKILL_V2_ORDER_STATUS_KEY + orderId,
                    OrderStatus.FAIL_FINAL,
                    1800, TimeUnit.SECONDS);
                log.error("订单重试耗尽, 置为 已失败, orderId={}", orderId);
            }
        }
    }

    private void handleDelayCheck(VoucherOrderMessage msg) {
        Long orderId   = msg.getOrderId();
        Long voucherId = msg.getVoucherId();
        Long userId    = msg.getUserId();

        VoucherOrder dbOrder = voucherOrderService.getById(orderId);
        String existsInDB = (dbOrder != null) ? "1" : "0";

        Long result = stringRedisTemplate.execute(
            rollbackScript,
            List.of(),
            String.valueOf(orderId),
            String.valueOf(voucherId),
            String.valueOf(userId),
            existsInDB
        );

        if (result != null && result == 1L) {
            log.info("延迟检查: 库存已回退, orderId={}", orderId);
        } else if (result != null && result == 2L) {
            log.info("延迟检查: DB已有订单, 已标记为已完成, orderId={}", orderId);
        } else {
            log.info("延迟检查: 无需处理, orderId={}", orderId);
        }
    }
}
