package com.example.helpdesk.controller;

import com.example.helpdesk.Result;
import com.example.helpdesk.client.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ClassName:EmbeddingTestController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/13 15:33
 * @Version 1.0
 */
@RestController
@RequiredArgsConstructor
public class EmbeddingTestController {
private final EmbeddingClient embeddingClient;

@GetMapping("/api/test/embed")
public Result<String> test(@RequestParam String text){
    float[] v = embeddingClient.embed(text);
    return Result.success("维度：" + v.length + ",前三个值是：" + v[0] + "," + v[1] + "," + v[2]);
}



}
