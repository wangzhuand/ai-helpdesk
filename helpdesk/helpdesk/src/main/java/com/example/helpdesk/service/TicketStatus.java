package com.example.helpdesk.service;

import java.util.Map;
import java.util.Set;

/**
 * ClassName:TicketStatus
 * Package:com.example.helpdesk.service
 * Description:
 * 工单状态机，所有合法流转状态
 * @Author 妄汐霜
 * @Create 2026/9/21 20:30
 * @Version 1.0
 */
public enum TicketStatus {
    OPEN,PROCESSING,RESOLVED,CLOSED,REOPENED;

    //状态机的规则表，每个状态能合法跳转到哪些状态
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = Map.of(
            OPEN,   Set.of(PROCESSING),
            PROCESSING, Set.of(RESOLVED),//处理中->已解决
            RESOLVED,Set.of(CLOSED,REOPENED),//已解决->已关闭，已解决->重新打开
            REOPENED,Set.of(PROCESSING),//重新打开->处理中
            CLOSED, Set.of()
    );

    //当前状态能否合法跳到target
    public boolean canTransitionTo(TicketStatus target){
        return ALLOWED.getOrDefault(this,Set.of()).contains(target);
    }





}
