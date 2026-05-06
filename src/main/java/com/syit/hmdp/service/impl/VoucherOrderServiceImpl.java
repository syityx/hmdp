package com.syit.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.VoucherOrder;
import com.syit.hmdp.mapper.VoucherOrderMapper;
import com.syit.hmdp.mq.VoucherOrderMessage;
import com.syit.hmdp.service.ISeckillVoucherService;
import com.syit.hmdp.service.IVoucherOrderService;
import com.syit.hmdp.utils.MqConstants;
import com.syit.hmdp.utils.SnowflakeIdWorker;
import com.syit.hmdp.utils.UserHolder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static com.syit.hmdp.utils.RedisConstants.*;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private SnowflakeIdWorker snowflakeIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_V2_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_RATE_LIMIT_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_ORDER_ROLLBACK_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_V2_SCRIPT = new DefaultRedisScript<>();
        SECKILL_V2_SCRIPT.setLocation(new ClassPathResource("seckill-v2.lua"));
        SECKILL_V2_SCRIPT.setResultType(Long.class);

        SECKILL_RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        SECKILL_RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("seckill_rate_limit.lua"));
        SECKILL_RATE_LIMIT_SCRIPT.setResultType(Long.class);

        SECKILL_ORDER_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ORDER_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill-order-rollback.lua"));
        SECKILL_ORDER_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1. Java 端预生成全局唯一 orderId
        long orderId = snowflakeIdWorker.nextId();

        // 2. 执行 Lua 脚本：预扣库存 + 判重复 + 写 PENDING 占位
        Long result = stringRedisTemplate.execute(
            SECKILL_V2_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(),
            userId.toString(),
            String.valueOf(orderId),
            String.valueOf(SECKILL_MAX_ORDERS_PER_USER)
        );
        if (result == null) {
            return Result.fail("Lua脚本执行异常");
        }
        int r = result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("已达购买上限");
        }
        if (r != 0) {
            return Result.fail("Lua未知错误");
        }

        long now = System.currentTimeMillis();
        log.debug("Lua预占成功, orderId={}, voucherId={}", orderId, voucherId);

        // 2. 发送正常下单消息到 RocketMQ
        VoucherOrderMessage orderMsg = new VoucherOrderMessage(
            orderId, voucherId, userId, now, MqConstants.TAG_ORDER);

        try {
            // 模拟 10% MQ 发送失败，测试回退链路
            if (orderId % 10 == 0) {
                throw new RuntimeException("模拟MQ故障, orderId末位=0");
            }
            rocketMQTemplate.syncSend(
                MqConstants.VOUCHER_ORDER_TOPIC + ":" + MqConstants.TAG_ORDER,
                JSONUtil.toJsonStr(orderMsg)
            );
        } catch (Exception e) {
            log.error("发送下单消息失败, 回退Redis预占, orderId={}", orderId, e);
            stringRedisTemplate.execute(
                SECKILL_ORDER_ROLLBACK_SCRIPT,
                Collections.emptyList(),
                String.valueOf(orderId),
                String.valueOf(voucherId),
                String.valueOf(userId),
                "0"
            );
            return Result.fail("下单繁忙，请稍后重试[mq故障]");
        }

        // 3. 发送延迟检查消息（5分钟后兜底）
        VoucherOrderMessage delayMsg = new VoucherOrderMessage(
            orderId, voucherId, userId, now, MqConstants.TAG_DELAY_CHECK);

        try {
            org.springframework.messaging.Message<String> message =
                org.springframework.messaging.support.MessageBuilder
                    .withPayload(JSONUtil.toJsonStr(delayMsg))
                    .build();
            rocketMQTemplate.syncSend(
                MqConstants.VOUCHER_ORDER_TOPIC + ":" + MqConstants.TAG_DELAY_CHECK,
                message,
                300_000L,
                MqConstants.DELAY_LEVEL_5_MINUTES
            );
        } catch (Exception e) {
            log.error("发送延迟检查消息失败, orderId={}", orderId, e);
        }

        return Result.ok(orderId);
    }

    @Override
    @CircuitBreaker(name = "seckillRedis", fallbackMethod = "seckillVoucherFallback")
    public Result seckillVoucherWithCircuitBreaker(Long voucherId) {
        return seckillVoucher(voucherId);
    }

    @SuppressWarnings("unused")
    private Result seckillVoucherFallback(Long voucherId, Exception e) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("dbFallback");
        if (!rateLimiter.acquirePermission()) {
            log.warn("降级路径限流触发, voucherId={}", voucherId);
            return Result.fail("系统繁忙，请稍后重试");
        }
        log.warn("熔断触发, 降级到纯DB方案, voucherId={}, cause={}", voucherId, e.getMessage());
        try {
            return seckillVoucherWithDB(voucherId);
        } catch (Exception ex) {
            log.error("降级方案失败, voucherId={}", voucherId, ex);
            return Result.fail("系统繁忙，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void create(VoucherOrder voucherOrder) {
        Long orderId   = voucherOrder.getId();
        Long voucherId = voucherOrder.getVoucherId();

        // DB 级别幂等兜底（Lua 已做，此处防御）
        if (getById(orderId) != null) {
            log.info("订单已存在，跳过, orderId={}", orderId);
            return;
        }

        // 扣减 MySQL 库存
        boolean success = seckillVoucherService.update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", voucherId)
            .gt("stock", 0)
            .update();
        if (!success) {
            throw new RuntimeException("DB扣减库存失败, orderId=" + orderId);
        }

        // 插入订单（id 主键唯一约束兜底）
        save(voucherOrder);
        log.info("订单写入DB成功, orderId={}, voucherId={}", orderId, voucherId);
    }

    // ==========================================================
    // 以下为纯 DB 方案（备用），流程独立，不受 MQ 重构影响
    // ==========================================================

    @Transactional
    public Result seckillVoucherWithDB(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "Lua未知错误");
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(snowflakeIdWorker.nextId());
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1);
        voucherOrder.setCreateTime(LocalDateTime.now());
        save(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }

    private boolean allowSeckillRequest(Long voucherId, Long userId) {
        long now = System.currentTimeMillis();
        String rateLimitKey = SECKILL_RATE_LIMIT_KEY + voucherId + ":" + userId;
        Long allowed = stringRedisTemplate.execute(
                SECKILL_RATE_LIMIT_SCRIPT,
                List.of(rateLimitKey),
                String.valueOf(now),
                String.valueOf(now - SECKILL_RATE_LIMIT_WINDOW_MILLIS),
                String.valueOf(SECKILL_RATE_LIMIT_MAX_REQUESTS),
            now + "-" + Thread.currentThread().getId(),
                String.valueOf(Math.max(1L, (SECKILL_RATE_LIMIT_WINDOW_MILLIS + 999L) / 1000L))
        );
        return Long.valueOf(1L).equals(allowed);
    }
}
