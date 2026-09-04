package com.example.helpdesk.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.entity.SysUser;
import com.example.helpdesk.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * ClassName:DataInitializer
 * Package:com.example.helpdesk.config
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 20:18
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private static final String PLACEHOLDER = "$2a$10$PLACEHOLDER_REPLACE_AT_FIRST_RUN";
    private final SysUserMapper sysUserMapper;


    @Override
    public void run(String... args) throws Exception {
        SysUser admin = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername,"admin")
        );
        if (admin != null) {
            boolean ok = new BCryptPasswordEncoder().matches("admin123", admin.getPasswordHash());
            if (!ok) {
                admin.setPasswordHash(new BCryptPasswordEncoder().encode("admin123"));
                sysUserMapper.updateById(admin);
                log.info("admin 密码已重置为 admin123");
            }
        }
    }
}
