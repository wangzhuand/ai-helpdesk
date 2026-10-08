package com.example.helpdesk.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ClassName:SystemMessagePusher
 * Package:com.example.helpdesk.component
 * Description:
 * 系统消息推送其：收到系统消息已创建事件后，把消息推给访客的SSE连接
 *
 * @Author 妄汐霜
 * @Create 2026/10/8 11:12
 * @Version 1.0
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMessagePusher {
    private final SseSessionRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,fallbackExecution = true)
    public void onSystemMessageCreated(SystemMessageCreatedEvent event){
        registry.push(event.getConversationId(),"system",event.getMessage());
    }





}
