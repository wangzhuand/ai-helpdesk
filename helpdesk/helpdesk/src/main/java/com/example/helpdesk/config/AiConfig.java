package com.example.helpdesk.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ClassName:AiConfig
 * Package:com.example.helpdesk.config
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/6 19:47
 * @Version 1.0
 */
@Configuration
public class AiConfig {
    @Bean
    ChatClient chatClient(ChatClient.Builder builder){
        return builder.build();
    }
}
