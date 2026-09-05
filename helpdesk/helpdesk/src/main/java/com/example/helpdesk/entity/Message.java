package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.lang.reflect.Type;
import java.time.LocalDateTime;

/**
 * ClassName:Message
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 21:21
 * @Version 1.0
 */
@Data
@TableName("message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String senderType;
    private String content;
    private LocalDateTime createdAt;
}
