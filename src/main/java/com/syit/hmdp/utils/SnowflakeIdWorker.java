package com.syit.hmdp.utils;

import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdWorker {

    private final cn.hutool.core.lang.Snowflake snowflake;

    public SnowflakeIdWorker() {
        this.snowflake = IdUtil.getSnowflake(1, 1);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
