package com.example.helpdesk.controller;

import com.example.helpdesk.Result;
import com.example.helpdesk.dto.SendMessageRequest;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * ClassName:ConversationController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 21:35
 * @Version 1.0
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversationService;

    //接口1：创建会话
    @PostMapping
    public Result<Long> create(){
        return Result.success(conversationService.createConversation());
    }

    //2.接口2：在会话里发消息
    @PostMapping("{conversationId}/messages")
    public Result<Message> sendMessage(@PathVariable Long conversationId, @Valid @RequestBody SendMessageRequest message){
        return Result.success(conversationService.sendMessage(conversationId,message));
    }


}
