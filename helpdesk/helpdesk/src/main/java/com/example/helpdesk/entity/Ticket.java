package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ClassName:Ticket
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/21 20:18
 * @Version 1.0
 */
@Data
@TableName("ticket")
public class Ticket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private Long conversationId;
    private String title;
    private String description;
    private String category;
    private Integer priority;
    private String status;
    private Long assigneeId;
    private LocalDateTime slaDeadline;
    @Version
    private Integer version;  //乐观锁
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
