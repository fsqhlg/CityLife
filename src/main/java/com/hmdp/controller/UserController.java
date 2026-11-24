package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Pattern;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码。
     * IP 维度 5 次/小时，手机号维度 1 次/分钟（在 UserServiceImpl 里做），防止短信轰炸。
     */
    @PostMapping("code")
    @RateLimit(prefix = "code:ip", limit = 5, windowMs = 3600000, dimension = RateLimit.Dimension.IP)
    public Result sendCode(@RequestParam("phone")
                           @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误") String phone) {
        // 发送短信验证码并保存到 Redis
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@Valid @RequestBody LoginFormDTO loginForm){
        // 校验验证码，登录成功后返回 token
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token){
        // 删除 Redis 中对应的登录 token
        return userService.logout(token);
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回（由 RefreshTokenInterceptor 从 Redis 取出后放入 UserHolder）
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * 查看某个用户的基本信息（个人主页用）
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return Result.ok();
        }
        return Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
    }

    /**
     * 用户签到
     */
    @PostMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    /**
     * 本月连续签到天数
     */
    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}
