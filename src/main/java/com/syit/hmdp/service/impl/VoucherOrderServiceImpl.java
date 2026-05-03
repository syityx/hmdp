package com.syit.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.VoucherOrder;
import com.syit.hmdp.mapper.VoucherOrderMapper;
// import com.syit.hmdp.mq.VoucherOrderMessage;
import com.syit.hmdp.service.ISeckillVoucherService;
import com.syit.hmdp.service.IVoucherOrderService;
import com.syit.hmdp.utils.MqConstants;
import com.syit.hmdp.utils.RedisWorker;
import com.syit.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

import java.sql.Time;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import cn.hutool.json.JSONUtil;
// import org.apache.rocketmq.spring.core.RocketMQTemplate;

import static com.syit.hmdp.utils.RedisConstants.SECKILL_RATE_LIMIT_KEY;
import static com.syit.hmdp.utils.RedisConstants.SECKILL_RATE_LIMIT_MAX_REQUESTS;
import static com.syit.hmdp.utils.RedisConstants.SECKILL_RATE_LIMIT_WINDOW_MILLIS;
import static com.syit.hmdp.utils.RedisConstants.SECKILL_V2_ORDER_KEY;
import static com.syit.hmdp.utils.RedisConstants.SECKILL_V2_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    // private RocketMQTemplate rocketMQTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_V2_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_RATE_LIMIT_SCRIPT;
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
    }
    private BlockingQueue<VoucherOrderTask> voucherOrderQueue = new LinkedBlockingQueue<>(1024 *1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * P54-使用stream实现消息队列，没看懂，没做
     */

    @PostConstruct
    private void init(){
        //
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            // log.debug("voucher order consumer started");
            while (true){
                try {
                    // 每次只 take 一次，避免把下一条任务误取走
                    VoucherOrderTask task = voucherOrderQueue.take();
                    VoucherOrder voucherOrder = task.getVoucherOrder();
                    long start = task.getEnqueueTime();
                    long now = System.currentTimeMillis();
                    long processCost = now - start;
                    
                    // 创建订单
                    // handleVoucherOrder(voucherOrder);
                    proxy.create(voucherOrder);
                    long totalCost = System.currentTimeMillis() - start;
                    log.info("{}-已经写入数据库--订单id={}--进入消费者耗时={}ms--总耗时={}ms", now, voucherOrder.getId(), processCost, totalCost);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        // 获取用户id
        // 独立的线程，不能重Threadlocal中获取了
        Long userId = voucherOrder.getUserId();
        // 创建分布式锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 锁过期时间1s
        boolean isLock = lock.tryLock(1, TimeUnit.MINUTES);
        if (!isLock){
            // 获取锁失败
            // log.error("不允许重复下单");
            return;
        }
        try {
            proxy.create(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>优惠券秒杀功能会存在超卖问题</p>
     * <ul>
     *   <li>
     *       <strong>悲观锁：</strong>线程安全问题一定会发生，添加同步锁，让线程串行执行
     *       <ul>
     *           <li>
     *               优点：简单   ；   缺点：性能一般
     *           </li>
     *       </ul>
     *   </li>
     *   <li>
     *       <strong>乐观锁：</strong>线程安全问题不一定会发生，不加锁，在更新时判断是否有其他线程在更改
     *       <ul>
     *          <li>
     *              CAS（compare and set）, 扣减库存操作的同时，判断stock > 0才执行
     *          </li>
     *          <li>
     *              性能好，存在成功率低的问题
     *          </li>
     *       </ul>
     *   </li>
     * </ul>
     */
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 滑动窗口限流
        // if (!allowSeckillRequest(voucherId, userId)) {
        //     return Result.fail("请求过于频繁，请稍后再试");
        // }
        // 1 执行lua脚本
        // Long result = stringRedisTemplate.execute(
        //         SECKILL_SCRIPT,
        //         Collections.emptyList(),
        //         voucherId.toString(),
        //         userId.toString()
        // );
        Long result = stringRedisTemplate.execute(
                // SECKILL_SCRIPT,
                SECKILL_V2_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 2 判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            // 2.1 !=0 没有购买资格
            // return Result.fail(r == 1 ? "库存不足" : "你已经购买过了，不能重复下单");
            // log.info("Lua脚本执行结果: {}", r);
            return Result.fail(r == 1 ? "库存不足" : "Lua未知错误");
        }
        // 2.2 ==0 有购买资格，下单信息保存到阻塞队列(线程池消费)
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisWorker.nextID("order");
        
        // 写入占位信息
        stringRedisTemplate.opsForSet().add(
            SECKILL_V2_ORDER_KEY + voucherId,
            userId.toString() + "-" + orderId
        );
        // =================================
        long start = System.currentTimeMillis();
        String stock = stringRedisTemplate.opsForValue().get(SECKILL_V2_STOCK_KEY + voucherId);
        log.debug("{}-已在redis预扣减库存--订单id={}--库存={}", start, orderId, stock);
        
        // =================================
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // try {
        //     rocketMQTemplate.syncSend(
        //         MqConstants.VOUCHER_ORDER_TOPIC,
        //         JSONUtil.toJsonStr(new VoucherOrderMessage(voucherOrder, start))
        //     );
        // } catch (Exception e) {
        //     // 自动流控，不会对系统造成过大压力
        //     log.error("发送秒杀订单消息失败, orderId={}", orderId, e);
        //     return Result.fail("下单繁忙，请稍后重试");
        // }

        return Result.ok(orderId);
    }

    public class VoucherOrderTask {
        private VoucherOrder voucherOrder;
        private long enqueueTime;

        public VoucherOrderTask(VoucherOrder voucherOrder, long enqueueTime) {
            this.voucherOrder = voucherOrder;
            this.enqueueTime = enqueueTime;
        }

        public VoucherOrder getVoucherOrder() {
            return voucherOrder;
        }

        public long getEnqueueTime() {
            return enqueueTime;
        }
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

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 没开始
//            return Result.fail("秒杀尚未开始");
//        }
//        // 3 判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已经结束
//            return Result.fail("秒杀已经结束");
//        }
//        // 4 判断库存
//        if (voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // 创建分布式锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + userId, stringRedisTemplate);
//        // 使用Redisson的锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//
//        // 锁过期时间1s
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            // 获取锁失败
//            return Result.fail("不允许重复下单 By 分布式锁");
//        }
//            // spring事务失效的可能性
//            // 获取代理对象，来调用create，才能使用spring来管理事务，只能锁到单体应用，对于分布式还是存在并发安全风险
//            // 多个jvm时候，有多个jvm锁监视器，不同线程可以同时获得synchronized锁
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.create(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void create(VoucherOrder voucherOrder) {
        long createMethodStart = System.currentTimeMillis();
        Long orderId = voucherOrder.getId();
        
        // 最小幂等：同一个订单号已经处理过就直接返回
        long getByIdStart = System.currentTimeMillis();
        if (getById(orderId) != null) {
            long getByIdCost = System.currentTimeMillis() - getByIdStart;
            log.debug("订单已处理，getById耗时={}ms, orderId={}", getByIdCost, orderId);
            log.info("订单已处理，忽略重复消息, orderId={}", orderId);
            return;
        }
        long getByIdCost = System.currentTimeMillis() - getByIdStart;
        log.debug("幂等性检查(getById)耗时={}ms, orderId={}", getByIdCost, orderId);
        
        // 一人一单
        // Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 停用一人一单限制
        // Long count = query().eq("user_id", userId)
        //         .eq("voucher_id", voucherId).count();
        // if (count > 0){
        //     return ;
        // }
        // 5 扣减库存
        long updateStart = System.currentTimeMillis();
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId)
//                .eq("stock", voucher.getStock())
                .gt("stock", 0)
                .update();
        long updateCost = System.currentTimeMillis() - updateStart;
        if (!success){
            log.debug("扣减库存失败，耗时={}ms, voucherId={}", updateCost, voucherId);
            // 扣减失败
            log.error("DB扣减库存失败");
            return ;
        }
        log.debug("扣减库存成功，耗时={}ms, voucherId={}", updateCost, voucherId);
        
        // 6 保存订单
        long saveStart = System.currentTimeMillis();
        save(voucherOrder);
        long saveCost = System.currentTimeMillis() - saveStart;
        long totalCreateCost = System.currentTimeMillis() - createMethodStart;
        log.debug("保存订单耗时={}ms, create总耗时={}ms, orderId={}", saveCost, totalCreateCost, orderId);
    }
}
