package com.syit.hmdp.utils;

public class MqConstants {
    private MqConstants() {}

    public static final String VOUCHER_ORDER_TOPIC = "voucher-order-topic";
    public static final String VOUCHER_ORDER_CONSUMER_GROUP = "voucher-order-consumer-group";

    public static final String TAG_ORDER       = "order";
    public static final String TAG_DELAY_CHECK = "delay-check";

    /** RocketMQ delay level 9 = 5 minutes */
    public static final int DELAY_LEVEL_5_MINUTES = 9;
}
