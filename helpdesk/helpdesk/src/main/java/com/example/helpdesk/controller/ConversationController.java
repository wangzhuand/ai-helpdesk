package com.example.helpdesk.controller;

import com.example.helpdesk.common.Result;
import com.example.helpdesk.dto.SendMessageRequest;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.service.AiService;
import com.example.helpdesk.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话相关接口。
 *
 * <p>本类里最难懂的是 {@link #sendMessage}，它是整个项目里唯一一段"异步/响应式"代码。
 * 读之前请先记住三句话：</p>
 * <ol>
 *   <li><b>subscribe = 登记规则然后走人</b>，不是"站在原地等结果"。规则有三条：
 *       每来一个 token / 出错 / 正常结束。</li>
 *   <li><b>Disposable = 挂断开关</b>。异步代码里方法早就 return 了，没法用 break/return 停下来，
 *       只能靠这个对象把"取消"信号传出去。</li>
 *   <li><b>AtomicReference = 一个能换内容的盒子</b>。因为 lambda 只能捕获 final 的局部变量，
 *       而"挂断开关"要等 subscribe 返回之后才拿得到，所以先用盒子占位。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final AiService aiService;

    // 接口1：创建会话（匿名访客），返回会话 id
    @PostMapping
    public Result<Long> create() {
        return Result.success(conversationService.createConversation());
    }

    /**
     * 接口2：在会话里发消息，AI 的回答以 SSE 流式返回。
     *
     * <p>整个方法要做 5 件事：① 存用户消息 ② 请求 DeepSeek ③ 每来一个 token 就转发给浏览器
     * ④ 全部吐完把完整回答存库 ⑤ 关连接。其中 ③ 要持续十几秒，所以必须异步。</p>
     */
    @PostMapping(value = "/{conversationId}/messages", produces = "text/event-stream;charset=utf-8")
    public SseEmitter sendMessage(@PathVariable Long conversationId, @Valid @RequestBody SendMessageRequest request) {
        // ① 访客消息立刻存库，拿到回执（前端要马上把"我"这条气泡显示出来）
        Message visitorMsg = conversationService.saveVisitorMessage(conversationId, request.getMessage());
        // 取最近 20 条历史，作为上下文
        List<Message> recent = conversationService.recentMessages(conversationId);

        // SseEmitter 就是"一条通向浏览器、可以慢慢往里写数据的长连接"，参数是最长 120 秒
        SseEmitter emitter = new SseEmitter(120_000L);

        // ===== 关键点 1：盒子（AtomicReference）=====
        // 为什么不直接写 Disposable d = ... ？
        //   a) lambda 只能捕获 final 的局部变量，而 d 要等下面 subscribe 返回才有值（还要被重新赋值）
        //   b) 这个盒子必须"每次请求各自一份"（局部变量），不能做成类成员变量 ——
        //      本类在 Spring 里是单例，多个访客同时聊天会互相覆盖开关，一个人的取消会误杀另一个人的连接
        // 附加好处：AtomicReference 保证"取出并清空"是一步完成的，并且跨线程能读到最新值
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        // ===== 关键点 2：挂断开关 =====
        // 这个动作会在"连接正常关闭 / 120 秒超时 / 出错"三种时机被调用。
        // dispose() 会把「取消」信号沿数据流的反方向一路传回 DeepSeek 的 HTTP 连接：
        // 连接一断，模型就不再生成 token，也就 不再计费 —— 这就是"防止用户关掉页面后还在烧钱"的机制。
        Runnable cancelUpstream = () -> {
            // getAndSet(null)：把开关从盒子里取出来，同时把盒子清空。
            // 这样第二次调用时拿到的是 null，直接跳过 —— 保证重复调用不出问题（幂等）
            Disposable d = subscription.getAndSet(null);
            if (d != null && !d.isDisposed()) {
                d.dispose();
                log.debug("客户端已断开，取消 LLM 流，conversationId:{}", conversationId);
            }
        };
        emitter.onCompletion(cancelUpstream);          // 连接正常结束时
        emitter.onTimeout(cancelUpstream);             // 120 秒超时时
        emitter.onError(e -> cancelUpstream.run());    // 连接出错时

        // 先把用户消息的回执推给前端。
        // 注意：这里用的是 checked 异常 IOException，和下面 token 转发处不同 —— 因为 send 的重载不同
        try {
            emitter.send(SseEmitter.event().name("visitor").data(objectMapper.writeValueAsString(visitorMsg)));
        } catch (IOException e) {
            // 连回执都发不出去，说明对面早就断了，直接返回，没必要再去请求模型
            log.debug("回执发送失败（客户端可能已经断开）: {}", e.getMessage());
            return emitter;
        }

        // 用来把一个个碎片 token 攒成完整回答，最后才能存库
        StringBuilder reply = new StringBuilder();

        // ===== 关键点 3：subscribe（登记规则，立刻返回）=====
        // 注意：chatStream(recent) 返回的 Flux 只是一份"将来会来很多字"的说明书，
        // 不调用 subscribe 它什么都不会发生（这叫冷流/惰性）。
        // subscribe 的三个参数就是三种情况下的处理规则，分别对应"数据来了 / 出错了 / 结束了"。
        Disposable disposable = aiService.chatStream(recent).subscribe(
                // 规则①（每来一个 token）：攒起来 + 转发给浏览器
                token -> {
                    reply.append(token);
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (Exception e) {
                        // 转发失败基本等于客户端已经断开 —— 立刻按挂断开关，停止烧 token
                        // 这是省钱最直接的一刀：不必等到整段回答生成完
                        log.debug("token 发送失败（客户端可能已经断开）: {}", e.getMessage());
                        cancelUpstream.run();
                    }
                },
                // 规则②（上游出错）：告诉浏览器出错了，然后关连接
                error -> {
                    log.error("流式输出失败", error);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("ai业务繁忙，请稍后重试"));
                    } catch (Exception e) {
                        log.debug("error 事件发送失败（客户端已断开）");
                    }
                    emitter.complete();
                },
                // 规则③（正常结束）：把攒好的完整回答存库 + 下发 done + 关连接
                // 注意：如果中途被 dispose 取消（用户断开），这条规则不会执行 ——
                // 也就是说半截回答不会入库，这是有意为之，避免用户刷新回来看到一句没说完的话
                () -> {
                    try {
                        Message aiMsg = conversationService.saveAiMessage(conversationId, reply.toString());
                        emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(aiMsg)));
                    } catch (Exception e) {
                        log.error("落库失败", e);
                    } finally {
                        emitter.complete();
                    }
                }
        );

        // 把刚拿到的开关放进盒子。回调可能在别的线程里同时读这个盒子，所以必须走 AtomicReference
        subscription.set(disposable);

        // 把这个 SSE 连接交给 Spring MVC 管理（挂起请求，异步返回三要素之一）
        return emitter;
    }

    // 接口3：会话历史消息，游标分页（lastId 为空表示取最新一页）
    @GetMapping("/{conversationId}/messages")
    public Result<List<Message>> history(@PathVariable Long conversationId
            , @RequestParam(required = false) Long lastId
            , @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(conversationService.listMessage(conversationId, lastId, size));
    }

}
