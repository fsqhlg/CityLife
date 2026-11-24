package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisRateLimiter;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisRateLimiter redisRateLimiter;

    private static final String LOGIN_FAIL_KEY = "login:fail:";
    private static final int LOGIN_FAIL_LIMIT = 5;

    /**
     * 发送手机验证码。
     * 验证码以 String 类型存到 Redis（key: login:code:手机号），并设置 2 分钟过期。
     */
    @Override
    public Result sendCode(String phone) {
        // 1. 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 1.1 同一个手机号 60 秒内只能发一次验证码，防止短信轰炸
        if (!redisRateLimiter.tryAcquire("rate:code:phone:" + phone, 1, 60_000)) {
            return Result.fail(ErrorCode.RATE_LIMIT, "验证码发送过于频繁，请 60 秒后再试");
        }

        // 2. 生成 6 位随机验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 保存验证码到 Redis，并设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4. 模拟发送短信：控制台打印验证码
        log.debug("发送短信验证码成功，手机号：{}，验证码：{}", phone, code);
        return Result.ok();
    }

    /**
     * 短信验证码登录。
     * 校验手机号 -> 比对 Redis 中的验证码 -> 查询用户（不存在则自动注册）
     * -> 生成 token，把脱敏后的用户信息以 Hash 存入 Redis，并设置 token 有效期。
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1. 校验手机号格式
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 2. 校验验证码格式（6 位数字或字母）
        String code = loginForm.getCode();
        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码格式错误！");
        }

        // 3. 从 Redis 中取出验证码并比对（key 过期时会返回 null）
        String failKey = LOGIN_FAIL_KEY + phone;
        String failValue = stringRedisTemplate.opsForValue().get(failKey);
        int failCount = 0;
        if (failValue != null) {
            try {
                failCount = Integer.parseInt(failValue);
            } catch (NumberFormatException ignored) {
                failCount = 0;
            }
        }
        if (failCount >= LOGIN_FAIL_LIMIT) {
            return Result.fail(ErrorCode.RATE_LIMIT, "尝试次数过多，请 15 分钟后再试");
        }

        String codeKey = LOGIN_CODE_KEY + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (cacheCode == null || !cacheCode.equals(code)) {
            recordLoginFailure(failKey);
            return Result.fail("验证码错误或已过期！");
        }

        // 4. 比对通过，删除验证码（一次性使用），并清空失败计数
        stringRedisTemplate.delete(codeKey);
        stringRedisTemplate.delete(failKey);

        // 5. 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 6. 用户不存在则自动注册
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 7. 生成登录 token（后续前端放在 authorization 请求头里带回）
        String token = UUID.randomUUID().toString(true);

        // 8. 把 User 脱敏为 UserDTO，再转成 Hash 存 Redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        // StringRedisTemplate 要求 key/value 都是 String，这里统一转成字符串
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? "" : fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 9. 给 token 设置有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 10. 返回 token 给前端存储
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isNotBlank(token)) {
            // 删除 Redis 中的登录 token，删除后该 token 立即失效
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    @Override
    public Result sign() {
        // 1. 当前用户
        Long userId = UserHolder.getUser().getId();

        // 2. 拼接 key：sign:{userId}:yyyyMM
        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        // 3. 今天是本月第几天，就把第几位（从 0 开始）设为 1
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();

        // 1. 取出本月到今天为止的所有签到位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        // 2. 从低位（今天）开始数连续 1 的个数
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            }
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 记录一次登录失败：15 分钟内累计 5 次就锁定。
     */
    private void recordLoginFailure(String failKey) {
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(failKey, 15, TimeUnit.MINUTES);
        }
    }

    /**
     * 根据手机号创建新用户，昵称随机生成
     */
    private User createUserWithPhone(String phone) {
        User user = new User()
                .setPhone(phone)
                .setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
