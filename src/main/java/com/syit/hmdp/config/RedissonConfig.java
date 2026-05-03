package com.syit.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson分布式锁原理 P43-45      <br>
 * 可重入（hash结构记录重入次数   <br>
 * 可重入 <br>
 * 超时续约（利用watchdog<br>
 * 主从一致性问题：多个独立的Redis节点都获取锁（成本较高，实现复杂 <br>
 * ==========================================   <br>
 * Redis分布式锁  <br>
 * 利用setnx的互斥性，利用ex避免死锁，释放时判断线程标识
 */
@Configuration
public class RedissonConfig {
    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean
    public RedissonClient redissonClient() {
        // 配置
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        if (redisPassword == null || redisPassword.isBlank()) {
            config.useSingleServer().setAddress(address);
        } else {
            config.useSingleServer().setAddress(address).setPassword(redisPassword);
        }
        // 创建对象
        return Redisson.create(config);
    }
}
