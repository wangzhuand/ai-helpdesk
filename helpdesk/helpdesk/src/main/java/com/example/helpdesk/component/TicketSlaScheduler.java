package com.example.helpdesk.component;

import com.example.helpdesk.common.TraceIds;
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

        // ★ W10-① 把当前线程（HTTP 请求线程）的 traceId 写进消息属性，
        // 消费者 SlaCheckConsumer 会取出来放进 MDC —— 这样"建工单"和"到点检查"的日志能串成一条线
        MessageBuilder<String> builder = MessageBuilder.withPayload(String.valueOf(ticketId));
        TraceIds.attach(builder);
        Message<String> message = builder.build();
        rocketMQTemplate.syncSend(
                TicketService.SLA_TOPIC,
                message,
                3000,
                TicketService.SLA_DELAY_LEVEL
        );
        log.info("工单{}已安排SLA检查：{}分钟后触发",ticketId,TicketService.SLA_MINUTES);

    }

}