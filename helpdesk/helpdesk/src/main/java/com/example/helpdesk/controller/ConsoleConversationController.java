package com.example.helpdesk.controller;

import com.example.helpdesk.common.Result;
import com.example.helpdesk.common.UserContext;
import com.example.helpdesk.component.SseSessionRegistry;
import com.example.helpdesk.dto.SendMessageRequest;
import com.example.helpdesk.entity.Conversation;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ClassName:ConsoleConversationController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/28 21:31
 * @Version 1.0
 */
@RestController
@RequestMapping("/api/console/conversations")
@RequiredArgsConstructor
public class ConsoleConversationController {
private final ConversationService conversationService;
private final SseSessionRegistry registry;

//会话列表 ：mine = false待接管池，mine = true 我的会话
    @GetMapping
    public Result<List<Conversation>> list(@RequestParam(defaultValue = "false") Boolean mine){
        return Result.success(conversationService.listForConsole(mine, UserContext.getUserId()));
    }

    //接管会话
    @PostMapping("/{id}/takeover")
    public Result<Void> takeOver(@PathVariable Long id){
        conversationService.takeOver(id,UserContext.getUserId());
        return Result.success();
    }

    //看这个会话的消息
    @GetMapping("/{id}/messages")
    public Result<List<Message>> messages(@PathVariable Long id,
                                          @RequestParam(required = false) Long lastId,
                                          @RequestParam(defaultValue = "20")Integer size
    ){
        return Result.success(conversationService.listMessage(id,lastId,size));
    }


    @PostMapping("/{id}/messages")
    public Result<Long> sendMessage(@PathVariable Long id, @Valid @RequestBody SendMessageRequest request) {
        //1.存库
        Message agentMsg = conversationService.saveAgentMessage(id, request.getMessage(),UserContext.getUserId());

        //2推给访客（不在线就什么都不做，消息已经存库）
        registry.push(id, "agent", agentMsg);

        return Result.success(agentMsg.getId());
    }

}
