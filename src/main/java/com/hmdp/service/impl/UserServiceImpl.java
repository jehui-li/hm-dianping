package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Override
    public Result sendCode2(String phone, HttpSession session) {
        // 校验手机号
        if (!RegexUtils.isPhoneInvalid(phone)) {
            // 校验不成功，返回
            return Result.fail("手机格式不对");
        }
        // 校验成功，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 存在session里
        session.setAttribute("code", code);
        // 发送验证码
        log.debug("验证码发送成功：" + code);
        return Result.ok();
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (!RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("<UNK>");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 放redis里，有有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送
        log.debug("你的验证码：" + code);
        return Result.ok();
    }

//    @Override
    public Result login2(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (!RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("<UNK>");
        }
        // 校验验证码
        String code = loginForm.getCode();
        if (code == null || !code.equals(session.getAttribute("code"))) {
            return Result.fail("<UNK>");
        }
        // 查询用户，没有，注册
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        // 把用户信息放session里
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user", userDTO);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (!RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        if (stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone) == null) {
            return Result.fail("请重新点发送验证码");
        }
        // 校验验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !code.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        User user = query().eq("phone", phone).one();
        // 用户不存在，注册
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        // redis和threadLocal只存部分信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // token是返回的，所以安全性
        String token = UUID.randomUUID().toString();
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, BeanUtil.beanToMap(userDTO));
        // 用户信息，过期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        save(user);
        return user;
    }
}
