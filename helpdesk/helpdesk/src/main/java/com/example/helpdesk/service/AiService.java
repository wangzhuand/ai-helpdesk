package com.example.helpdesk.service;

import com.example.helpdesk.entity.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * ClassName:AiService
 * Package:com.example.helpdesk.service
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/7 14:54
 * @Version 1.0
 */
@Service
@RequiredArgsConstructor
public class AiService {
    private final ChatClient chatClient;

    public String chat(List<Message> history) {
        List<org.springframework.ai.chat.messages.Message> turns =
                history.stream().map(m ->{
                    if("VISITOR".equals(m.getSenderType())){
                        return(org.springframework.ai.chat.messages.Message) new UserMessage(m.getContent());
                    }
                    if("AI".equals(m.getSenderType()) || "AGENT".equals(m.getSenderType())){
                        return(org.springframework.ai.chat.messages.Message) new AssistantMessage(m.getContent());
                    }
                    return null;
                }).filter(Objects::nonNull).toList();


        return chatClient.prompt().system("你是一个在线客服机器人，请用中文友好，简洁的回答问题。不知道答案时就说不知道，不要瞎编")
                .messages(turns).call().content();

    }

}
