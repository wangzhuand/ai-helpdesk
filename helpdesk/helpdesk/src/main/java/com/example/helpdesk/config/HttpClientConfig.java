package com.example.helpdesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP 客户端配置。
 * RestClient 必须是单例：原来每次调用都 RestClient.create()，等于每次新建连接池，连接无法复用。
 *
 * @Author 妄汐霜
 * @Create 2026/10/24
 * @Version 1.0
 */
@Configuration
public class HttpClientConfig {

    // 建立连接最多等 3 秒。内网 1 秒就够，公网要留 TLS 握手的时间
    private static final int CONNECT_TIMEOUT_MS = 3000;

    // 连上后 15 秒收不到数据就断开。embedding 正常 1 秒内返回，15 秒已经很宽松
    private static final int READ_TIMEOUT_MS = 15000;

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }
}
