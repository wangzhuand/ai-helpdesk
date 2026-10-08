package com.example.helpdesk.component;

import com.example.helpdesk.entity.Message;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ClassName:SystemMessageCreatedEvent
 * Package:com.example.helpdesk.component
 * Description:
 *
 * 系统消息已创建事件
 * 用途：saveSystemMessage,落库后把事件发出去
 * 由监听器在【事务提交之后】或[没有事务时立刻]推给访客
 *
 *
 * @Author 妄汐霜
 * @Create 2026/10/8 11:05
 * @Version 1.0
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemMessageCreatedEvent {
    private Long conversationId;
    private Message message;
}
