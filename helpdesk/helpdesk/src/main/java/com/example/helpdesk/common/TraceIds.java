package com.example.helpdesk.common;

import org.slf4j.MDC;
import org.springframework.messaging.support.MessageBuilder;

/**
 * traceId 工具类。
 * 一次请求可能跨多线程（HTTP 线程 -> MQ 消费线程），MDC 是线程私有的，跨线程就断了，所以要手动搬。
 * 搬法：HTTP 入口放进 MDC -> 发消息前写进消息属性 -> 消费时取出来再放进新线程的 MDC。
 *
 * @Author 妄汐霜
 * @Create 2026/10/20
 * @Version 1.0
 */
public final class TraceIds {

    // MDC 和消息属性共用的 key，两边必须一致，否则消费端取不到
    public static final String KEY = "traceId";

    // HTTP 头，上游传了就用它的，方便跨系统串起来
    public static final String HEADER = "X-Trace-Id";

    private TraceIds() {
    }

    // 当前线程的 traceId，没有返回 null
    public static String current() {
        return MDC.get(KEY);
    }

    // 放进当前线程的 MDC，null 或空串忽略
    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(KEY, traceId);
        }
    }

    // 清空当前线程的 MDC，必须在 finally 里调，否则线程复用会污染下一个请求
    public static void clear() {
        MDC.remove(KEY);
    }

    // 把当前线程的 traceId 写进消息属性，消费端才读得到
    public static void attach(MessageBuilder<?> builder) {
        String traceId = current();
        if (traceId != null && !traceId.isBlank()) {
            builder.setHeader(KEY, traceId);
        }
    }
}
