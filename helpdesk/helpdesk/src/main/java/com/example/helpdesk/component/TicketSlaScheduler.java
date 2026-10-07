package com.example.helpdesk.component;

import com.example.helpdesk.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ClassName:TicketSlaScheduler
 * Package:com.example.helpdesk.component
 * Description:
 *
 * 工单SLA定时检查--在工单创建事务提交成功以后发一条延迟消息
 *
 *
 * @Author 妄汐霜
 * @Create 2026/10/7 20:37
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketSlaScheduler {
    private final RocketMQTemplate rocketMQTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketCreated(TicketCreatedEvent event){
        Long ticketId = event.getTicketId();

        Message<String> message = MessageBuilder.withPayload(String.valueOf(ticketId)).build();
        rocketMQTemplate.syncSend(
                TicketService.SLA_TOPIC,
                message,
                3000,
                TicketService.SLA_DELAY_LEVEL
        );
        log.info("工单{}已安排SLA检查：{}分钟后触发",ticketId,TicketService.SLA_MINUTES);

    }

}