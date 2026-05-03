package com.syit.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class RedisWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final long COUNT_BITS = 32L;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private final StringRedisTemplate stringRedisTemplate;

    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成全局唯一id，使用redis自增，每天一个key，id=时间戳＋计数 <br>
     * 生成策略 <br>
     *  - UUID  <br>
     *  - Redis自增
     * @param key
     * @return
     */
    public long nextID(String key) {
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        // Generate a sequence number partitioned by day and business key.
        String date = now.format(DATE_FORMATTER);
        String redisKey = "icr:" + key + ":" + date;
        Long sequence = stringRedisTemplate.opsForValue().increment(redisKey);


        return (timestamp << COUNT_BITS) | sequence;
    }
}
