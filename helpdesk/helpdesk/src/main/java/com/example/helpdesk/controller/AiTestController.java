package com.example.helpdesk.controller;

import com.example.helpdesk.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ClassName:AiTestController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/6 16:47
 * @Version 1.0
 */
@RestController
@RequiredArgsConstructor
public class AiTestController {
    private final ChatClient chatClient;

    @GetMapping("/api/test/ai")
    public Result<String> ask(@RequestParam String q){
        String content = chatClient.prompt().user(q).call().content();
        return Result.success(content);
    }

}
