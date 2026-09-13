package com.example.helpdesk.client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

/**
 * ClassName:EmbeddingClient
 * Package:com.example.helpdesk.client
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/12 18:40
 * @Version 1.0
 */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {
    @Value("${embedding.base-url}")
    private String baseUrl;

    @Value("${embedding.model}")
    private String model;

    @Value("${embedding.api-key}")
    private String apikey;

    private final ObjectMapper objectMapper;
public float[] embed(String text) {
    try {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", text
        );
        String response = RestClient.create().post().uri(baseUrl + "/embeddings").header("Authorization", "Bearer " + apikey)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);

        JsonNode path = objectMapper.readTree(response).path("data").get(0).path("embedding");

        float[] vector = new float[path.size()];
        for (int i = 0; i < path.size(); i++) {
            vector[i] = (float) path.get(i).asDouble();
        }
        return vector;
    } catch (Exception e) {
        throw new RuntimeException("调用向量接口失败：" + e.getMessage(), e);
    }
}
}
