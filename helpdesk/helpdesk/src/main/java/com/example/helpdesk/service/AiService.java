package com.example.helpdesk.service;

import com.example.helpdesk.dto.RetrievedChunk;
import com.example.helpdesk.entity.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * ClassName:AiService
 * Package:com.example.helpdesk.service
 * Description:
 *      w5起开始改成rag：把检索到的知识块拼到system prompt.要求模型只依据资料回答
 * @Author 妄汐霜
 * @Create 2026/9/7 14:54
 * @Version 1.0
 */
@Service
@RequiredArgsConstructor
public class AiService {
    private final ChatClient chatClient;
    //system prompt模板：这是RAG的最重要的三条规则：
    /*
    1。只依据资料
    2.没资料就说没找到+转人工
    3.别提参考资料这四个字，显得自然点
     */
    private static final String SYSTEM_TEMPLATE = """
            你是[示例商城]的在线客服，请用中文友好，简介得回答用户问题
            
            请依据下面提供的[参考资料]回答:
            1.如果参考资料里有答案，就照着参考资料回答，不要添加资料里没有的信息；
            2.如果参考资料里没有相关内容，就直接说明"我理解不了你的意思"，并建议用户转人工，不要自己编造     
            3.回答中不要出现"参考资料""根据资料显示"这类字眼，直接自然的回复就行
            
            
            【参考资料】
            %s
            """;

   /*
   流式回答

   @param history 历史消息列表
   @param references 检索到的知识块

    */
    public Flux<String> chatStream(List<Message> history, List<RetrievedChunk> references){
        String system = SYSTEM_TEMPLATE.formatted(buildReferenceContext(references));
        return chatClient.prompt().system(system)
                .messages(toAiMessage(history))
                .stream()
                .content();
    }

//把Message实体翻译成SPring AI 认识的对话消息
    private List<org.springframework.ai.chat.messages.Message> toAiMessage(List<Message> history){
        return history.stream().map(m -> {
            if("VISITOR".equals(m.getSenderType())){
                return (org.springframework.ai.chat.messages.Message) new UserMessage(m.getContent());
            }
            if("AI".equals(m.getSenderType()) || "AGENT".equals(m.getSenderType())){
                return (org.springframework.ai.chat.messages.Message) new AssistantMessage(m.getContent());
            }
            return null;
        }).filter(Objects::nonNull).toList();
    }
    //把检索到的知识块拼成编号文本，供模型参考
    private String buildReferenceContext(List<RetrievedChunk> refs){
        if (refs == null || refs.isEmpty()){
            return "(没有检索到相关资料)";
        }
        StringBuilder sb = new StringBuilder();
        for(int i = 0;i < refs.size();i++){
            RetrievedChunk c = refs.get(i);
            sb.append(i+1).append(". <<").append(c.getDocTitle()).append(">>");
            sb.append(c.getContent()).append("\n\n");

        }
        return sb.toString();
    }






//    //ai流式交流方法
//    public Flux<String> chatStream(List<Message> history){
//        List<org.springframework.ai.chat.messages.Message> turns =
//        history.stream().map(m ->{
//            if("VISITOR".equals(m.getSenderType())){
//                return (org.springframework.ai.chat.messages.Message) new UserMessage(m.getContent());
//            }
//            if("AI".equals(m.getSenderType()) || "AGENT".equals(m.getSenderType())){
//                return (org.springframework.ai.chat.messages.Message) new AssistantMessage(m.getContent());
//            }
//            return null;
//        }).filter(Objects::nonNull).toList();
//
//        return chatClient.prompt().system("你是一个在线客服机器人，请用中文友好，简洁的回答问题。不知道答案时就说不知道，不要瞎编")
//                .messages(turns).stream().content();//call是直接一整个把内容返回，stream是一个字一个字返回
//
//    }





}
