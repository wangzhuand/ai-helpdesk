package com.example.helpdesk.component;

import com.example.helpdesk.common.TraceIds;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 基于 Redis ZSet 的延迟队列。
 * score 存到期时间戳，Redis 自动按 score 排序，所以永远知道最早到点的是哪个。
 * 投递：ZADD；消费：定时 ZRANGEBYSCORE 捞到点的；抢占：ZREM 返回 1 才算拿到。
 * 能丢的用 ZSet，不能丢的（SLA、订单关单）还是走 MQ 延迟消息。
 *
 * @Author 妄汐霜
 * @Create 2026/10/20
 * @Version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDelayQueue {

    private final StringRedisTemplate redis;
    /** ★ 单向依赖：本类需要它查会话/发系统消息，它不反向依赖本类（投递直接用它自己的 redis 字段） */
    private final ConversationService conversationService;

    // ===================== 常量（投递方也要用，所以是 public） =====================

    /** 所有延迟任务共用一个 ZSet，member 用前缀区分任务类型 */
    public static final String DELAY_QUEUE_KEY = "delay:queue";

    /** 任务类型前缀：访客消息无回复检查。member 形如 conv:reply:123 */
    public static final String REPLY_TASK_PREFIX = "conv:reply:";

    // 访客发消息后多久检查有没有人回复（10 分钟）
    public static final long REPLY_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    /** 每轮最多捞多少条，防止一次拉太多把线程卡住 */
    private static final int BATCH_SIZE = 100;

    // ===================== ① 轮询 =====================

    // 每秒扫一次。用 fixedDelay 不用 fixedRate：fixedRate 在上一轮跑超时时会叠多轮，多个线程抢同一批任务
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        long now = System.currentTimeMillis();

        Set<String> due;
        try {
            // 只捞 score ≤ now 的 —— 即"时间已经到了"的那几张便利贴
            due = redis.opsForZSet().rangeByScore(DELAY_QUEUE_KEY, 0, now, 0, BATCH_SIZE);
        } catch (Exception e) {
            // Redis 抖一下不能让调度线程挂掉
            log.warn("[延迟队列]拉取到期任务失败", e);
            return;
        }

        if (due == null || due.isEmpty()) {
            return;
        }

        for (String member : due) {
            // ★★ 抢占：ZREM 返回 1 才说明"这张便利贴是我撕下来的"
            // 多实例部署时，另一个实例可能已经撕走了，返回 0 直接跳过
            Long removed;
            try {
                removed = redis.opsForZSet().remove(DELAY_QUEUE_KEY, member);
            } catch (Exception e) {
                log.warn("[延迟队列]抢占任务失败，member={}", member, e);
                continue;
            }
            if (removed == null || removed == 0) {
                continue;
            }

            // ★ W10-① 调度线程没有 HTTP 请求上下文（MDC 是空的），
            // 用 member 造一个 traceId —— 这样"贴便利贴"和"执行任务"的日志能对上，
            // 同一批里的不同任务也能区分开
            TraceIds.set("delay-" + member);
            try {
                handle(member);
            } catch (Exception e) {
                // ★ 单条任务失败不能影响同一批里的其他任务
                log.warn("[延迟队列]执行任务失败，member={}", member, e);
            } finally {
                TraceIds.clear();
            }
        }
    }

    // ===================== ② 分发 =====================

    private void handle(String member) {
        if (member.startsWith(REPLY_TASK_PREFIX)) {
            Long conversationId = Long.valueOf(member.substring(REPLY_TASK_PREFIX.length()));
            handleReplyTimeout(conversationId);
            return;
        }
        // 将来加别的任务类型，在这里续 if
        log.warn("[延迟队列]未知的任务类型，member={}", member);
    }

    // ===================== ③ 具体业务 =====================

    // 访客消息超时未回复的检查。
    // 到点必须再查一遍：这 10 分钟里 AI 或坐席可能已经回复了，但 ZSet 里的任务不会自己消失。
    // 和 SlaCheckConsumer 到点后再查工单是否 OPEN 一个道理。
    private void handleReplyTimeout(Long conversationId) {
        List<Message> recent;
        try {
            recent = conversationService.recentMessages(conversationId);
        } catch (Exception e) {
            log.warn("[延迟队列]查询会话消息失败，conversationId={}", conversationId, e);
            return;
        }

        if (recent == null || recent.isEmpty()) {
            return;
        }

        // recentMessages 内部已 reverse 成正序，所以最后一条就是最新的
        Message last = recent.get(recent.size() - 1);
        if (!"VISITOR".equals(last.getSenderType())) {
            // 最后一条是 AI / AGENT / SYSTEM → 已经有人回应过了，不需要提醒
            log.info("[延迟队列]会话 {} 已有回复（最后一条={}），跳过提醒",
                    conversationId, last.getSenderType());
            return;
        }

        conversationService.saveSystemMessage(conversationId,
                "您的问题已收到，我们正在为您处理，请稍候");
        log.info("[延迟队列]会话 {} 超过 {} 分钟无人回复，已发送提醒",
                conversationId, REPLY_TIMEOUT_MILLIS / 60000);
    }
}
