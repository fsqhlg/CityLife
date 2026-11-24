package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送手机验证码，验证码保存到 Redis 并设置过期时间
     *
     * @param phone 手机号
     * @return 发送结果
     */
    Result sendCode(String phone);

    /**
     * 短信验证码登录：校验验证码 -> 查询/注册用户 -> 生成 token 并把用户信息存入 Redis
     *
     * @param loginForm 登录参数（手机号 + 验证码）
     * @return 登录结果，成功时 data 为登录 token
     */
    Result login(LoginFormDTO loginForm);

    /**
     * 登出：删除 Redis 中的登录 token
     *
     * @param token 前端携带的登录 token（authorization 请求头）
     * @return 登出结果
     */
    Result logout(String token);

    /**
     * 用户签到：把本月第几天对应的 bit 置 1
     *
     * @return 签到结果
     */
    Result sign();

    /**
     * 统计本月连续签到天数
     *
     * @return 连续签到天数
     */
    Result signCount();
}
