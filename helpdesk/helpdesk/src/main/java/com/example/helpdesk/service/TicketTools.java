package com.example.helpdesk.service;

import com.example.helpdesk.entity.Ticket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * ClassName:TicketTools
 * Package:com.example.helpdesk.service
 * Description:
 *  给大模型调用的工具集
 *
 *  模型不执行任何真的操作，模型只是调用工具，真正执行代码的是java工具，返回值会回灌给模型，模型再基于它生成最后的答案
 *
 * @Author 妄汐霜
 * @Create 2026/9/28 14:36
 * @Version 1.0
 */
@Slf4j
@RequiredArgsConstructor
public class TicketTools {
    private final Long conversationId;
    private final TicketService ticketService;
    private final ConversationService conversationService;


    /*
    mock数据的订单库，
    真实接入时只要把queryOrder内部换成订单服务就行，工具和模型完全不用改
     */
    private static final Map<String,String> MOCK_ORDERS=Map.of(
            "A123456", "订单 A123456：已于 2026-09-20 签收；退款于 2026-09-21 发起，当前状态为处理中，预计 3 个工作日内原路退回。",
            "B789012", "订单 B789012：已于 2026-09-25 发出，当前在途，预计 2026-09-29 送达。",
            "C555666", "订单 C555666：已于 2026-09-10 完成，该订单没有退款记录。"
    );

    //工具一：查订单
    @Tool(description = "根据订单号查询订单状态与退款进度。当用户提到具体订单号，或询问物流进度，" + "退款是否到账，退款多久到账时使用")
    //这个description是写给大模型看的
    public String queryOrder(@ToolParam(description = "订单号,例如 A123456") String orderNo){
        log.info("工具调用queryOrder,conversationId={},orderNo={}",conversationId,orderNo);

        if (orderNo == null || orderNo.isBlank()){
            return "未提供订单号，请让用户提供订单号再查询";
        }
        String info = MOCK_ORDERS.get(orderNo.trim().toUpperCase());
        if(info == null){
            return "未查询到订单号为" + orderNo + "的订单，请让用户确认订单号是否正确";
        }
        return info;
    }

    //工具二：建工单
    @Tool(description = "创建客服工单并转交人工处理。当用户的问题知识库无法解答、或用户明确要求建单、催办、投诉时使用。")
    public String createTicket(
    @ToolParam(description = "工单标题，简短概括问题，例如：退款未到帐")String title,
    @ToolParam(description = "工单分类，只能是：退款/物流/账号/其他")String category,
    @ToolParam(description = "问题的详细描述，包含用户提供的关键信息（如订单号）")String description
    ){
        log.info("工具调用 createTicket, conversationId={}, title={}, category={}", conversationId, title, category);

        //幂等判断，同一会话已有未关闭的工单就不再重复创建
        Ticket existing = ticketService.findOpenByConversation(conversationId);
        if(existing != null){
            return "该会话中已有处理中的工单，工单号" + existing.getTicketNo() + ",无需重复创建";
        }
        //operatorId 传null:工单时AI创建的，不是坐席创建的（日志里操作人为空，也可约定用0表示ai）
        Long ticketId = ticketService.create(title,category,description,conversationId,null);
        Ticket ticket = ticketService.getById(ticketId);
        //往会话里插一条SYSTEM消息，访客在聊天界面也能看到一创建工单
        conversationService.saveSystemMessage(conversationId,"已为您创建工单" + ticket.getTicketNo() + ",我们会尽快处理。");
        return "已成功创建工单，工单号" + ticket.getTicketNo()+ "。请告知用户工单号，并说明客服会尽快处理.";

    }



    //工具三：转人工
    @Tool(description = "把当前会话转接给人工客服吗。当用户明确要求人工，表达强烈不满，"+ "或资料无法解决且当用户反复追问时使用。")
    public String escalateToHuman(@ToolParam(description = "转人工的原因，简要说明，比如：用户要求转人工")String reason){
        log.info("工具调用 escalateToHuman,conversationId={},reason={}",conversationId,reason);

        conversationService.escalateToAgent(conversationId);

        conversationService.saveSystemMessage(conversationId,"已为您转接人工客服，请稍后");
        return "已把会话转接给人工客服 （原因："+reason+"）。请告知用户人工客服即将接入，请稍后";

    }










































}