package com.example.helpdesk.service;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.common.BusinessException;

import com.example.helpdesk.entity.Conversation;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.entity.Visitor;
import com.example.helpdesk.mapper.ConversationMapper;
import com.example.helpdesk.mapper.MessageMapper;
import com.example.helpdesk.mapper.VisitorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * ClassName:ConversationService
 * Package:com.example.helpdesk.service
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 21:42
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationMapper conversationMapper;
    private final VisitorMapper visitorMapper;
    private final MessageMapper messageMapper;
    private final AiService aiService;

    public Long createConversation() {
        //1.先创建一个访客
        Visitor visitor = new Visitor();
        visitorMapper.insert(visitor);
        //2.用刚刚创建的访客id创建一场会话
        Conversation conversation = new Conversation();
        conversation.setVisitorId(visitor.getId());
        conversation.setStatus("AI");
        conversationMapper.insert(conversation);

        return conversation.getId();
    }

    //接口2：发送消息
    public Message saveVisitorMessage(Long conversationId, String content) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }

        Message message1 = new Message();
        message1.setConversationId(conversationId);
        message1.setSenderType("VISITOR");
        message1.setContent(content);
        message1.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message1);

        conversation.setLastMessageAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        return message1;
    }


    //取最近20条作为上下文
    public List<Message> recentMessages(Long conversationId) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT 20");
        List<Message> recent = messageMapper.selectList(wrapper);
        Collections.reverse(recent);//转为正序
        return recent;
    }

    //调ai，失败就兜底
//        String reply;
//        try {
//            reply = aiService.chat(recent);
//        }catch (Exception e){
//            log.error("AI 调用失败",e);
//            reply = "AI当前繁忙，请稍后再试，或点击转人工";
//        }

    public Message saveAiMessage(Long conversationId, String content) {
        Message aiMsg = new Message();
        aiMsg.setConversationId(conversationId);
        aiMsg.setSenderType("AI");
        aiMsg.setContent(content);
        aiMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(aiMsg);
        return aiMsg;
    }

//        SendMessageResponse response = new SendMessageResponse();
//        response.setVisitorMsg(message1);
//        response.setAiMsg(aiMsg);
//        return response;


    //接口3：会话分页
    public List<Message> listMessage(Long conversationId, Long lastId, Integer size) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        //限制size的大小，如果超过50就按50算
        if (size == null || size <= 0) {
            size = 20;
        }
        if (size > 50) {
            size = 50;
        }
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + size);

        if (lastId != null) {
            queryWrapper.lt(Message::getId, lastId);
        }
        return messageMapper.selectList(queryWrapper);

    }
}