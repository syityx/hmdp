package com.syit.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.syit.hmdp.utils.RedisConstants.*;


/**
 * redis工具类
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

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
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
    public <R, ID> R queryWithSynchronzied(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
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

        boolean gotLock = lock.tryLock();
        if (gotLock) {
            try {
                // 3.1 双重检查，防止其他线程已完成重建
                json = stringRedisTemplate.opsForValue().get(KEY);
                if (StrUtil.isNotBlank(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if (json != null) {
                    return null;
                }
                // 3.2 查询数据库
                R r = dbFallback.apply(id);
                if (r == null) {
                    stringRedisTemplate.opsForValue().set(KEY, "", time, timeUnit);
                    return null;
                }
                // 3.3 写入缓存
                stringRedisTemplate.opsForValue().set(KEY, JSONUtil.toJsonStr(r), time, timeUnit);
                return r;
            } finally {
                lock.unlock();
                if (!lock.hasQueuedThreads()) {
                    lockMap.remove(KEY, lock);
                }
            }
        }

        // 4. 抢锁失败，休眠后递归重试（下次大概率命中缓存）
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断时直接重试一次，不 sleep
        }
        return queryWithReentrantLock(prefix, id, type, dbFallback, time, timeUnit);
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

    /**
     * 逻辑过期解决缓存击穿（Redisson 分布式锁版本）。
     * 数据永不过期（无Redis TTL），靠 RedisData.expireTime 判断是否过期。
     * 过期时抢到分布式锁的线程重建缓存，未抢到的返回旧数据。
     */
    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                             Long time, TimeUnit timeUnit) {
        String KEY = prefix + id;

        // 1. 从redis查询缓存数据
        String json = stringRedisTemplate.opsForValue().get(KEY);
        // 2. 未命中（缓存从未预热），直接返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 3. 命中，解析RedisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);

        // 4. 判断是否逻辑过期,未过期,返回r
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }

        // 5. 已过期——尝试获取分布式锁重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);

        boolean gotLock = lock.tryLock();
        if (gotLock) {
            try {
                // 5.1 双重检查：可能已有线程重建完毕
                json = stringRedisTemplate.opsForValue().get(KEY);
                if (StrUtil.isNotBlank(json)) {
                    redisData = JSONUtil.toBean(json, RedisData.class);
                    if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                        return JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
                    }
                }
                // 5.2 查询数据库，写入缓存
                R newR = dbFallback.apply(id);
                if (newR != null) {
                    this.setWithLogicalExpire(KEY, newR, time, timeUnit);
                }
                return newR;
            } finally {
                lock.unlock();
            }
        }

        // 6. 没抢到锁，返回旧数据（过期但可用，避免缓存击穿）
        return r;
    }

}
