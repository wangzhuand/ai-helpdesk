package com.example.helpdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.common.BusinessException;
import com.example.helpdesk.common.JwtUtil;
import com.example.helpdesk.dto.LoginRequest;
import com.example.helpdesk.dto.LoginResponse;
import com.example.helpdesk.entity.SysUser;
import com.example.helpdesk.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * ClassName:AuthService
 * Package:com.example.helpdesk.service
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 19:47
 * @Version 1.0
 */
@Service
@RequiredArgsConstructor
public class AuthService {
    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest request) {
        //1.先去数据库查用户
        SysUser sysUser = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername())
        );

        //2.如果用户不存在
        if (sysUser == null) {
            throw new BusinessException("用户不存在");
        }
        //3.如果账号被禁用
        if(sysUser.getStatus() == null || sysUser.getStatus() != 1){
            throw  new BusinessException("该账号状态异常");
        }
        //4.如果密码错误
        if(!new  BCryptPasswordEncoder().matches(request.getPassword(),sysUser.getPasswordHash())){
            throw new BusinessException("密码错误");
        }

        //如果都匹配
        String token = jwtUtil.createToken(sysUser.getId(),sysUser.getRole());
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setToken(token);
        loginResponse.setNickname(sysUser.getNickname());
        loginResponse.setRole(sysUser.getRole());
        return loginResponse;
    }


}
