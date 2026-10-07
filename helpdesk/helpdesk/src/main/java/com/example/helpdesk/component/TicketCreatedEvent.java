package com.example.helpdesk.component;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ClassName:TicketCreatedEvent
 * Package:com.example.helpdesk.component
 * Description:
 *
 * 工单创建事件
 * 用途：TicketService.create(),落库成功后，把这个事件发出去
 * 由监听器再事务提交之后发MQ延迟消息（具体方法在TicketSlaScheduler）
 *
 *
 * @Author 妄汐霜
 * @Create 2026/10/7 20:23
 * @Version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketCreatedEvent {
    private Long ticketId;
}
