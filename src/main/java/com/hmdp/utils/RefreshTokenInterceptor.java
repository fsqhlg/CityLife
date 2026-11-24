package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 刷新 token 拦截器（第一道拦截器，order = 0，拦截所有路径）
 * <p>
 * 从请求头 authorization 中取出 token：
 * 1. token 为空或 Redis 中查不到 -> 什么都不做，直接放行（是否登录交给 LoginInterceptor 判断）；
 * 2. 查到了 -> 把 Hash 还原成 UserDTO 放入 UserHolder，并刷新 token 有效期。
 * <p>
 * 这样“需要登录的接口”和“不需要登录的接口”都能拿到当前用户，
 * 而且用户只要在活跃，token 就不会过期。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }


    @Override
    public boolean preHandle (HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            //没有token，说明未登录，直接放行，由LoginInterceptor决定是否拦截；
            return true;

        }
        // 2. 基于 token 获取 Redis 中的用户信息
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (userMap.isEmpty()) {
            // token 无效（已过期或已登出），同样直接放行
            return true;
        }

        // 3. 把 Hash 数据转成 UserDTO，保存到 ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        // 4. 刷新 token 有效期（用户活跃期间保持登录）
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束，清理 ThreadLocal，防止内存泄漏和线程复用导致的数据串号
        UserHolder.removeUser();
    }
}
