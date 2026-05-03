package com.syit.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.LoginFormDTO;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.dto.UserDTO;
import com.syit.hmdp.entity.User;
import com.syit.hmdp.mapper.UserMapper;
import com.syit.hmdp.service.IUserService;
import com.syit.hmdp.utils.RegexUtils;
import com.syit.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpSession;

import javax.swing.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static com.syit.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author syit
 * @date 2026-03-10
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {

        // 是否开启校验手机号（正则）
        // if (RegexUtils.isPhoneInvalid(phone)){
        //     return Result.fail("手机号格式错误");
        // }

        // 生成验证码（随机6位数字）
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到session（2min过期）
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, 2, TimeUnit.MINUTES);

        // 发送验证码
        log.debug("发送短信验证码成功，验证码: {}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();

        // 是否开启校验手机号（正则）
        // if (RegexUtils.isPhoneInvalid(phone)){
        //     return Result.fail("手机号格式错误");
        // }

        // 验证码登录
        User user = new User();
        if (loginForm.getPassword() == null){
            //        Object cachecode = session.getAttribute("code");//最好定义为常量
            //        修改为从redis中查询验证码
            String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            String code = loginForm.getCode();
            if (cachecode == null || !cachecode.equals(code)) {
                return Result.fail("验证码错误");
            }
            //        3.符合，根据手机号查询用户
            user = query().eq("phone", phone).one();
        }else{
//            密码登录
            user = query().eq("phone", phone).eq("password", loginForm.getPassword()).one();
        }
//        4.没有用户，创建用户
        if (user == null){
            user = createUserWithPhone(phone);
        }
//        5.保存用户信息到session，使用的是UserDTO消除敏感信息
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.copyProperties(user, userDTO);
//        session.setAttribute("user", userDTO);
//        5.保存用户信息到redis
//        5.1 生成随机token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
//        5.2 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        对象转Map
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue == null ? null : fieldValue.toString()));
//        5.3 存储数据
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
//        5.4 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30000, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId.toString() + format;
        int dayOfMonth = now.getDayOfMonth() - 1;
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth, true);
        return Result.ok();
    }

    public Result signCount(){
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId.toString() + format;
        int dayOfMonth = now.getDayOfMonth() - 1;
        int idx = dayOfMonth;
        int cnt = 0;

        Boolean ifSigned = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth);
        if (!ifSigned) {
            return Result.ok("今天还没有签到?");
        }
//        String toDay = ifSigned ? "今天已经签到" : "今天还没有签到";
//        log.error(toDay);
        while (idx >= 0){
            Boolean bit = stringRedisTemplate.opsForValue().getBit(key, idx);
            if (bit == null || !bit){
                return Result.ok("今天已经签到" + "已经连续签到" + cnt + "天");
            }else {
                cnt++;
            }
            idx = idx - 1;
        }
//        本月全勤
        String str = "今天已经签到" + "已经连续签到" + cnt + "天，本月目前全勤";
        return Result.ok(str);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("syit_" + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
