package com.syit.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.syit.hmdp.utils.RedisConstants.*;


/**
 * redis工具�?
 */

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        // 需要把对象序列化为str
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        // TODO 逻辑过期设添加redis字段
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /**
     *  解决缓存穿透问题，查询数据库后，结果为空也写入redis，设置过期时间
     *  泛型、函数传�?
     * @param keyPrefix 前缀
     * @param id （店铺）id
     * @param type 返回值类�?
     * @param dbFallback 查询函数
     * @param time 过期时间
     * @param timeUnit 过期时间单位
     * @return 查询结果（对象）
     * @param <R> 查询结果类型
     * @param <ID> id类型：int、long
     */
    public <R, ID> R queryWithPassThrogh(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                         Long time, TimeUnit timeUnit) {
        String KEY = keyPrefix + id;

        // 1.尝试从redis中查询商铺缓存,,这里使用String类型
        String json = stringRedisTemplate.opsForValue().get(KEY);
        // 2.如果存在，直接返回JSON -> Obj),,,命中缓存
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 3.0 如果是空字符�?
        if (json != null) {
            return null;
        }
        // 3.如果不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 4.不存在，返回错误
        if (r == null) {
            // 空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(KEY, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.存在，写入redis并返回，缓存重建
        this.set(KEY, r, time, timeUnit);

        return r;
    }


    /**
     *
     * @param prefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithMutex(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                    Long time, TimeUnit timeUnit) {
        String KEY = prefix + id;

        // 1 尝试从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(KEY);
        // 2 命中缓存
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 2.1 命中空字符串
        if (json != null) {
            return null;
        }
        // 3 缓存重建
        // 3.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;

        // int retryCount = 0;
        // int maxRetry = 50;  // 最多重试50次，100-250ms 超时

        synchronized(lockKey.intern()){
            // 3.2 再次检查缓存，防止重建过程中其他线程已经重建完毕，避免重复重建
            json = stringRedisTemplate.opsForValue().get(KEY);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }
            // 3.3 没有命中，查询数据库
            r = dbFallback.apply(id);
            // 4 不存在，返回错误
            if (r == null) {
                // 空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(KEY, "", time, timeUnit);
                return null;
            }
            // 5 存在，写入redis并返回，缓存重建
            stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
            return r;
        }
    }

    /**
     * 基于 ReentrantLock 的本地互斥锁解决缓存击穿（单JVM场景）。
     * 相比 synchronized：支持 tryLock 超时、可中断、公平锁，避免线程堆积。
     * 相比 Redisson 分布式锁：免网络开销，适合单机/主从部署。
     */
    public <R, ID> R queryWithReentrantLock(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                             Long time, TimeUnit timeUnit) {
        String KEY = prefix + id;

        // 1. 尝试从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(KEY);
        // 2. 命中缓存
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 2.1 命中空字符串
        if (json != null) {
            return null;
        }

        // 3. 获取本地互斥锁
        ReentrantLock lock = lockMap.computeIfAbsent(KEY, k -> new ReentrantLock());

        try {
            // 3.1 tryLock 等待最多 3 秒，拿不到则降级直接查库
            boolean gotLock = lock.tryLock(3, TimeUnit.SECONDS);
            if (gotLock) {
                try {
                    // 3.2 双重检查，防止其他线程已完成重建
                    json = stringRedisTemplate.opsForValue().get(KEY);
                    if (StrUtil.isNotBlank(json)) {
                        return JSONUtil.toBean(json, type);
                    }
                    if (json != null) {
                        return null;
                    }
                    // 3.3 查询数据库
                    R r = dbFallback.apply(id);
                    if (r == null) {
                        stringRedisTemplate.opsForValue().set(KEY, "", time, timeUnit);
                        return null;
                    }
                    // 3.4 写入缓存
                    stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
                    return r;
                } finally {
                    lock.unlock();
                    // 清理无等待者的锁，防止 map 内存泄漏
                    if (!lock.hasQueuedThreads()) {
                        lockMap.remove(KEY, lock);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. 拿不到锁或中断，降级直接查库
        R r = dbFallback.apply(id);
        if (r != null) {
            stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
        }
        return r;
    }

    public <R, ID> R queryWithRedissonLock(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                           Long time, TimeUnit timeUnit) {
        String KEY = prefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);

        int retryCount = 0;
        int maxRetry = 10;

        while (retryCount < maxRetry) {
            // 1. 尝试从redis中查询商铺缓存
            String json = stringRedisTemplate.opsForValue().get(KEY);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }

            // 2. 尝试获取Redisson分布式锁（非阻塞，watchdog自动续约）
            boolean gotLock = lock.tryLock();
            if (gotLock) {
                try {
                    // 2.1 双重检查
                    json = stringRedisTemplate.opsForValue().get(KEY);
                    if (StrUtil.isNotBlank(json)) {
                        return JSONUtil.toBean(json, type);
                    }
                    if (json != null) {
                        return null;
                    }
                    // 2.2 查询数据库
                    R r = dbFallback.apply(id);
                    if (r == null) {
                        stringRedisTemplate.opsForValue().set(KEY, "", time, timeUnit);
                        return null;
                    }
                    // 2.3 写入缓存并返回
                    stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
                    return r;
                } finally {
                    lock.unlock();
                }
            }

            // 3. 没拿到锁，休眠后重试读缓存
            retryCount++;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 重试耗尽，降级：直接查库
        R r = dbFallback.apply(id);
        if (r != null) {
            stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
        }
        return r;
    }

    public <R, ID> R queryWithRedis(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                    Long time, TimeUnit timeUnit){
        String KEY = prefix + id;
        // 1 尝试从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(KEY);
        // 2 命中缓存
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 2.1 命中空字符串
        if (json != null) {
            return null;
        }
        R r = dbFallback.apply(id);
        if (r == null) {
                // 空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(KEY, "", time, timeUnit);
                return null;
            }
            // 5 存在，写入redis并返回，缓存重建
        stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
        return r;
    }

    


    // 获取互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unLock(String key){
        return stringRedisTemplate.delete(key);
    }

    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                         Long time, TimeUnit timeUnit) {
        // TODO 逻辑过期解决缓存击穿
        return null;
    }

}
