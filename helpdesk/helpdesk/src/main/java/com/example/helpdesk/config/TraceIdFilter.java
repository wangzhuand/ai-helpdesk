package com.example.helpdesk.config;

import com.example.helpdesk.common.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个 HTTP 请求生成 traceId 放进 MDC。
 * 用 Filter 而不是 Interceptor：Filter 更靠前，SSE 长连接、404 也能覆盖到。
 *
 * @Author 妄汐霜
 * @Create 2026/10/20
 * @Version 1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 上游传了就用它的，没有就自己生成 16 位
        String traceId = request.getHeader(TraceIds.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        TraceIds.set(traceId);
        // 回写响应头，前端拿着这个 id 就能来对账
        response.setHeader(TraceIds.HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 线程复用，不清会污染下一个请求
            TraceIds.clear();
        }
    }
}
