package com.hmdp.aspect;

import com.hmdp.annotation.RateLimit;
import com.hmdp.common.BizException;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisRateLimiter;
import com.hmdp.utils.UserHolder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 限流切面：解析维度 -> 调 Redis 滑动窗口 -> 拒绝时抛 BizException(429)。
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class RateLimitAspect {

    @Resource
    private RedisRateLimiter redisRateLimiter;

    @Resource
    private MeterRegistry meterRegistry;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String identity = resolveIdentity(rateLimit.dimension());
        String key = "rate:" + rateLimit.prefix() + ":" + rateLimit.dimension().name().toLowerCase() + ":" + identity;

        boolean allowed = redisRateLimiter.tryAcquire(key, rateLimit.limit(), rateLimit.windowMs());
        if (!allowed) {
            meterRegistry.counter("rate_limit_blocked_total", "prefix", rateLimit.prefix()).increment();
            log.warn("触发限流：prefix={}, dimension={}, identity={}", rateLimit.prefix(), rateLimit.dimension(), identity);
            throw new BizException(ErrorCode.RATE_LIMIT);
        }
        return joinPoint.proceed();
    }

    private String resolveIdentity(RateLimit.Dimension dimension) {
        if (dimension == RateLimit.Dimension.USER) {
            UserDTO user = UserHolder.getUser();
            if (user != null && user.getId() != null) {
                return user.getId().toString();
            }
        }
        return clientIp();
    }

    private String clientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
