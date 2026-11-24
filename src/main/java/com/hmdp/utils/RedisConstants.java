package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String FOLLOW_KEY = "follow:";
    public static final String CACHE_SHOP_TYPE_KEY = "shop:type";

    /**
     * M2：Redis Stream 本地 outbox 与转发消费者组。
     * 生产者在 Lua 扣减库存的同一段脚本里 XADD，保证“扣减 + 写 outbox”原子完成；
     * 再由 SeckillOutboxRelay 转发到 RocketMQ，RocketMQ 才是真正的订单消费队列。
     */
    public static final String SECKILL_OUTBOX_KEY = "seckill:outbox";
    public static final String SECKILL_OUTBOX_GROUP = "seckill-outbox-cg";
    public static final String SECKILL_OUTBOX_DLQ_KEY = "seckill:outbox:dlq";

    /**
     * M3：扣减流水。Hash：field=orderId，value=userId|voucherId|status|createTimeMillis|retry
     */
    public static final String SECKILL_LEDGER_KEY = "seckill:ledger:";

    /**
     * M1：超时关单延迟队列。ZSet：member=orderId，score=应关闭时间（epoch millis）。
     */
    public static final String SECKILL_DELAY_KEY = "seckill:order:delay";

    /**
     * M1：库存回补幂等标记。Set：member=orderId，处理过回补的订单不再重复加库存。
     */
    public static final String SECKILL_RETURNED_KEY = "seckill:returned";

    /**
     * M3：初始库存，用于对账/排查超卖。String。
     */
    public static final String SECKILL_STOCK_INIT_KEY = "seckill:stock:init:";

    /**
     * M6：店铺布隆过滤器。Redis BitMap。
     */
    public static final String BLOOM_SHOP_KEY = "bloom:shop";

    /**
     * 用户已购集合前缀（与 Lua 中保持一致，Java 侧查询/补偿时复用）。
     */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
}
