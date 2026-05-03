package com.syit.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.syit.hmdp.dto.LoginFormDTO;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
