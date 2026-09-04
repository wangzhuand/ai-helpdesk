package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;

/**
 * ClassName:SysUser
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 17:59
 * @Version 1.0
 */
@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
}
