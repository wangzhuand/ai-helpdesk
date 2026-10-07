package com.example.helpdesk.component;

import com.example.helpdesk.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.checkerframework.checker.units.qual.C;
import org.springframework.stereotype.Component;

/**
 * ClassName:SlaCheckConsumer
 * Package:com.example.helpdesk.component
 * Description:
 *工单SLA检查消费者，延迟消息到点后，检查工单是否还没解决，是则自动升级
 *
 * @Author 妄汐霜
 * @Create 2026/10/7 21:12
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = TicketService.SLA_TOPIC,consumerGroup = "ticket-sla-group")
public class SlaCheckConsumer implements RocketMQListener<String> {

    private final TicketService ticketService;
    @Override
    public void onMessage(String body) {
    //1.解析：消息体是纯数字字符串，坏消息重试也没用
        Long ticketId;
        try {
            ticketId = Long.parseLong(body.trim());
        }catch (Exception e){
            log.error("SLA检查消息格式不对，丢弃：{}",body,e);
            return;
        }
        log.info("收到SLA检查消息：ticketId = {}",ticketId);

        //2.调业务方法（状态判断和幂等都在内部）
        boolean escalated = ticketService.escalateBySla(ticketId);

        //3.按结果不同打不同日志，方便排查
        if (escalated){
            log.warn("工单{}SLA超时未解决，已自动升级优先级",ticketId);
        }else {
            log.info("工单{}无需升级，跳过",ticketId);
        }




    }
}
