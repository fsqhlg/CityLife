package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器（第二道拦截器，order = 1）
 * <p>
 * 只负责判断“当前请求有没有登录用户”：
 * 用户信息由 RefreshTokenInterceptor 提前从 Redis 取出并放入 UserHolder，
 * 这里只需检查 UserHolder，为空就返回 401。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            // 未登录：返回 401 状态码，不放行；前端 axios 响应拦截器会跳转到登录页
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
