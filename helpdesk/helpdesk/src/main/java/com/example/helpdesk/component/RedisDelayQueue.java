package com.example.helpdesk.component;

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
 * ClassName:RedisDelayQueue
 * Package:com.example.helpdesk.component
 * Description: 基于 Redis ZSet 的延迟队列（W9-⑥）
 *
 * <p>原理：ZSet 的 score 存"到期时间戳"，Redis 自动按 score 排序。
 * <ul>
 *   <li>投递：{@code ZADD delay:queue <到期时间> <member>}（由投递方直接调用，见 ConversationService.saveVisitorMessage）</li>
 *   <li>消费：定时 {@code ZRANGEBYSCORE 0 now} 只捞"已到点"的</li>
 *   <li>抢占：{@code ZREM} 返回 1 才是我的活（多实例下天然防重复）</li>
 * </ul>
 *
 * <p>★ 和 MQ 延迟消息的分工：<b>能丢的用 ZSet，不能丢的用 MQ。</b>
 * 这里的"无人回复提醒"丢了只是少提醒一次，所以 ZSet 够用；
 * SLA 升级、订单关单那类不能丢的，仍然走 MQ 延迟消息。
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

    /** 访客发消息后多久检查有没有人回复（10 分钟） */
    public static final long REPLY_TIMEOUT_MILLIS = 10 * 1000L;

    /** 每轮最多捞多少条，防止一次拉太多把线程卡住 */
    private static final int BATCH_SIZE = 100;

    // ===================== ① 轮询 =====================

    /**
     * 每秒扫一次"便利贴墙"。
     *
     * <p>★ 用 fixedDelay 而不是 fixedRate：fixedRate 在上一轮跑超时的情况下会叠加多轮，
     * 多个线程同时捞同一批任务；fixedDelay 保证"上一轮跑完才计时"。
     */
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

            try {
                handle(member);
            } catch (Exception e) {
                // ★ 单条任务失败不能影响同一批里的其他任务
                log.warn("[延迟队列]执行任务失败，member={}", member, e);
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

    /**
     * 访客消息超时未回复的检查。
     *
     * <p>★ 为什么"到点还要再查一遍"：便利贴只认时间，不认业务 ——
     * 这 10 分钟里 AI 或坐席可能已经回复了，但 ZSet 里的任务不会自动消失。
     * 这和 SlaCheckConsumer 到点后还要查"工单还是不是 OPEN"是同一个道理。
     */
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
