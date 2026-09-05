package com.example.helpdesk.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.common.BusinessException;
import com.example.helpdesk.dto.SendMessageRequest;
import com.example.helpdesk.entity.Conversation;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.entity.Visitor;
import com.example.helpdesk.mapper.ConversationMapper;
import com.example.helpdesk.mapper.MessageMapper;
import com.example.helpdesk.mapper.VisitorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationMapper conversationMapper;
    private final VisitorMapper visitorMapper;
    private final MessageMapper messageMapper;

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
    public Message sendMessage(Long conversationId, SendMessageRequest message){
        Conversation conversation = conversationMapper.selectById(conversationId);
        if(conversation == null){
            throw new BusinessException("会话不存在");
        }
        Message message1 = new Message();
        message1.setConversationId(conversationId);
        message1.setSenderType("VISITOR");
        message1.setContent(message.getMessage());
        message1.setCreatedAt(LocalDateTime.now());

        messageMapper.insert(message1);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);

        return message1;

    }

    //接口3：会话分页
    public List<Message> listMessage(Long conversationId, Long lastId, Integer size) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if(conversation == null){
            throw new BusinessException("会话不存在");
        }
        //限制size的大小，如果超过50就按50算
        if(size == null || size <=0){
            size = 20;
        }
        if (size >50) {
            size =50;
        }
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId,conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT "+size);

        if(lastId != null){
            queryWrapper.lt(Message::getId,lastId);
        }
        return messageMapper.selectList(queryWrapper);

    }
}
