package com.syit.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 布隆过滤器工具（基于 Redisson {@link RBloomFilter}）。
 *
 * <p>{@code tryInit} 幂等，可在每次使用前调用。
 *
 * <p>注意：标准布隆过滤器不支持元素删除。对低频删除场景（如店铺下架），
 * 残留的 false positive 仅导致一次缓存/DB 穿透，影响可控。
 */
@Component
@Slf4j
public class BloomFilterHelper {

    private final RedissonClient redissonClient;

    public BloomFilterHelper(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public RBloomFilter<String> getFilter(String name,
                                           long expectedInsertions,
                                           double falseProbability) {
        RBloomFilter<String> filter = redissonClient.getBloomFilter(name);
        filter.tryInit(expectedInsertions, falseProbability);
        return filter;
    }

    public void add(String name, String key,
                    long expectedInsertions, double falseProbability) {
        getFilter(name, expectedInsertions, falseProbability).add(key);
    }

    public boolean contains(String name, String key,
                            long expectedInsertions, double falseProbability) {
        return getFilter(name, expectedInsertions, falseProbability).contains(key);
    }

    /** 过滤器是否为空（刚创建或被误删后重建）。 */
    public boolean isEmpty(String name, long expectedInsertions, double falseProbability) {
        return getFilter(name, expectedInsertions, falseProbability).count() == 0;
    }
}
