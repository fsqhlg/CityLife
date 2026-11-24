package com.hmdp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解：由 RateLimitAspect 拦截，Redis 滑动窗口实现。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** key 前缀，例如 seckill:user */
    String prefix();

    /** 窗口内允许的请求数 */
    int limit() default 10;

    /** 窗口大小，毫秒 */
    long windowMs() default 1000;

    /** 限流维度：按用户或按 IP */
    Dimension dimension() default Dimension.USER;

    enum Dimension {
        USER,
        IP
    }
}
