package com.syit.hmdp.utils;

/**
 * 秒杀订单异步处理流程状态常量（Redis 存储值）。
 * 与 DB 业务状态（1=未支付,2=已支付,3=已核销,4=已取消,5=退款中,6=已退款）完全分离。
 */
public final class OrderStatus {
    private OrderStatus() {}

    /** Lua 已预占库存，订单占位写入 Redis，等待消费者处理 */
    public static final String PENDING       = "待处理";
    /** 消费者已认领，正在执行 create 写入 DB */
    public static final String PROCESSING    = "处理中";
    /** 订单已成功写入 MySQL */
    public static final String SUCCESS       = "已完成";
    /** create 失败，等待 MQ 重投 */
    public static final String FAIL_RETRYING = "重试中";
    /** 重试次数耗尽，不再处理 */
    public static final String FAIL_FINAL    = "已失败";

    /** 消费者最大重试次数 */
    public static final int MAX_RETRY_COUNT = 3;
}
