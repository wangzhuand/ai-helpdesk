package com.example.helpdesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ClassName:TicketLog
 * Package:com.example.helpdesk.entity
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/21 20:23
 * @Version 1.0
 */
@Data
@TableName("ticket_log")
public class TicketLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    private Long operatorId;
    private String action;
    private String toStatus;
    private String remark;
    private LocalDateTime createdAt;
    private String fromStatus;
}
