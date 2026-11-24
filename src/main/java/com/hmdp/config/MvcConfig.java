package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * Spring MVC 配置：注册登录相关拦截器
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 第一道拦截器：所有请求都走，负责从 Redis 取登录用户、刷新 token 有效期，但从不拦截
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // 2. 第二道拦截器：只负责校验是否登录，未登录返回 401
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                // 放行：登录相关接口、无需登录即可浏览的业务接口、静态资源
                .excludePathPatterns(
                        // 登录相关
                        "/user/code",
                        "/user/login",
                        // 无需登录即可浏览的业务接口
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/blog/hot",
                        "/upload/**",
                        // 静态资源与错误页
                        "/static/**",
                        "/css/**",
                        "/js/**",
                        "/imgs/**",
                        "/favicon.ico",
                        "/error"
                )
                .order(1);
    }
}
