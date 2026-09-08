package com.example.helpdesk.controller;

import com.example.helpdesk.Result;
import com.example.helpdesk.dto.SendMessageResponse;
import com.example.helpdesk.dto.SendMessageRequest;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.entity.Visitor;
import com.example.helpdesk.service.AiService;
import com.example.helpdesk.service.ConversationService;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.antlr.runtime.Token;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * ClassName:ConversationController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 21:35
 * @Version 1.0
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final java.util.concurrent.ExecutorService aiExecutor = java.util.concurrent.Executors.newCachedThreadPool();


    //接口1：创建会话
    @PostMapping
    public Result<Long> create(){
        return Result.success(conversationService.createConversation());
    }

    //2.接口2：在会话里发消息（流式版本，ai一个字一个字回复给前端）
    @PostMapping(value = "/{conversationId}/messages",produces = "text/event-stream;charset=utf-8")
    public SseEmitter sendMessage(@PathVariable Long conversationId, @Valid @RequestBody SendMessageRequest request){
        //访客消息立刻存库并返回
        Message VisitotMsg = conversationService.saveVisitorMessage(conversationId,request.getMessage());
        //取上下文，包含用户的话和一些背景prompt
        List<Message> recent = conversationService.recentMessages(conversationId);
        SseEmitter emitter = new SseEmitter(120_000L);//设置长连接最多120秒
        aiExecutor.execute(() -> {
            StringBuilder reply = new StringBuilder();
            try {
                emitter.send(SseEmitter.event().name("visitor").data(objectMapper.writeValueAsString(VisitotMsg)));
                //把ai的话逐字推出
                aiService.chatStream(recent).toIterable().forEach(token -> {
                    reply.append(token);
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    }catch (Exception ignore){}
                });
                //生成完毕，ai完整回答入数据库，并告诉前端
                Message aiMsg = conversationService.saveAiMessage(conversationId,reply.toString());
                emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(aiMsg)));
            }catch (Exception e){
                log.error("流式输出失败",e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("Ai服务繁忙，请稍后重试"));
                }catch (Exception ignore){}
            }finally{
                emitter.complete();
            }


        });
        return emitter;



    }




    //3.接口3：会话分页
    @GetMapping("/{conversationId}/messages")
    public Result<List<Message>> history(@PathVariable Long conversationId
            ,@RequestParam(required = false) Long lastId
            ,@RequestParam(defaultValue = "20") Integer size)
    {
        return Result.success(conversationService.listMessage(conversationId,lastId,size));
    }



}
