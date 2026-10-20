package com.example.helpdesk.controller;

import com.example.helpdesk.common.Result;
import com.example.helpdesk.component.SseSessionRegistry;
import com.example.helpdesk.dto.ReferenceItem;
import com.example.helpdesk.dto.RetrievedChunk;
import com.example.helpdesk.dto.SendMessageRequest;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisAccessor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private final RetrievalService retrievalService;
    private final TicketService ticketService;
    private final SseSessionRegistry registry;
    private final StringRedisTemplate stringRedisTemplate;
    //LLM限流
    private static final int LLM_RATE_LIMIT = 10;
    //LLM限流的key前缀
    private static final String LLM_RATE_KEY_PREFIX = "rate:llm:conv";

    //令牌桶的lua脚本
    private static final String TOKEN_BUCKET_LUA= """
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local want = tonumber(ARGV[4])
            
            local bucket = redis.call('HMGET',KEYS[1],'tokens','lastRefill')
            local tokens = tonumber(bucket[1])
            local lastRefill = tonumber(bucket[2])
            
            if tokens == nil then 
               tokens = capacity
               lastRefill = now
            end
            
            local elapsed =(now - lastRefill) / 1000
            tokens = math.min(capacity,tokens + elapsed * rate)
            
            if tokens < want then 
                redis.call('HMSET',KEYS[1],'tokens',tokens,'lastRefill',now)
                redis.call('EXPIRE',KEYS[1],60)
                return 0
            end
            
            tokens = tokens - want
            redis.call('HMSET',KEYS[1],'tokens',tokens,'lastRefill',now)
            redis.call('EXPIRE',KEYS[1],60)    
            return 1
            """;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;
    static{
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setScriptText(TOKEN_BUCKET_LUA);
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);   //脚本return的是整数
    }

    //令牌桶容量：最多能攒几个
    private static final int BUCKET_CAPACITY = 1;

    //令牌桶速率：
    private static final double BUCKET_RATE = 0.02;










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

        //w6：查会话状态，决定走agent还是人工
        boolean humanMode = conversationService.isHumanMode(conversationId);
        // SseEmitter 就是"一条通向浏览器、可以慢慢往里写数据的长连接"，参数是最长 120 秒
        SseEmitter emitter = new SseEmitter(120_000L);

        //断开回调（两路都要注册）
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
        //两路都要发，否则前端不显示我的气泡
        try {
            emitter.send(SseEmitter.event().name("visitor").data(objectMapper.writeValueAsString(visitorMsg)));
        } catch (IOException e) {
            // 连回执都发不出去，说明对面早就断了，直接返回，没必要再去请求模型
            log.debug("回执发送失败（客户端可能已经断开）: {}", e.getMessage());
            return emitter;
        }




        //人工接管，不检索，不调模型，只告诉前端等人工回复，然后关链接
        //放在检索之前，可以省调一次embedding + 两次ES查询
        if (humanMode) {
            try {
                emitter.send(SseEmitter.event().name("waiting")
                        .data("已转接人工客服，坐席会尽快回复，请稍候"));
            } catch (IOException e) {
                log.debug("waiting 发送失败: {}", e.getMessage());
            }
            emitter.complete();
            log.info("会话 {} 已转人工，跳过 AI 回答", conversationId);
            return emitter;
        }



    //*********一下是AI分支的逻辑
        //LLM限流，同一会话中每分钟最多N次
        if(!tryAcquire(conversationId)){
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("你发得太快了，请稍等几秒钟再试"));
            }catch (IOException e){
                log.debug("限流提示发送失败，客户端可能已经断开：{}",e.getMessage());
            }
            emitter.complete();
            return emitter;

        }




        // 取最近 20 条历史，作为上下文
        List<Message> recent = conversationService.recentMessages(conversationId);

        //检索参考资料
        List<RetrievedChunk> refs;
        try {
            refs = retrievalService.search(request.getMessage(),"hybrid",3);
        }catch (Exception e){
            log.error("检索失败，降级为无资料回答",e);
            refs = List.of();
        }



        if(!refs.isEmpty()){
            try {
                emitter.send(SseEmitter.event().name("references")
                        .data(objectMapper.writeValueAsString(toReferenceItems(refs))));
            } catch (IOException e) {
                log.debug("参考资料发送失败（客户端可能已经断开）: {}", e.getMessage());
                return emitter;
            }
        }



        // 用来把一个个碎片 token 攒成完整回答，最后才能存库
        StringBuilder reply = new StringBuilder();

        // ===== 关键点 3：subscribe（登记规则，立刻返回）=====
        // 注意：chatStream(recent) 返回的 Flux 只是一份"将来会来很多字"的说明书，
        // 不调用 subscribe 它什么都不会发生（这叫冷流/惰性）。
        // subscribe 的三个参数就是三种情况下的处理规则，分别对应"数据来了 / 出错了 / 结束了"。

        //w6第三步：造一个绑定当前会话的工具实例
        TicketTools ticketTools = new TicketTools(conversationId, ticketService, conversationService,stringRedisTemplate);

        Disposable disposable = aiService.chatStream(recent, refs,ticketTools).subscribe(
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

    /*LLM限流方式一：
    固定窗口限流：同一会话每分钟最多LLM_RATE_LIMIT次。
    固定窗口的实现方法：把分钟编进key里，同一分钟所有请求落在同一个key上，跨到下一分钟，key就变了，计数器从0开始，这就是天然的窗口切换
     */
//    private boolean tryAcquire(Long conversationId){
//        //1.拼key
//        String key =  LLM_RATE_KEY_PREFIX + ":" + conversationId + ":" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
//
//        //2.自增计数，这是本分钟的第几次
//        Long count =  stringRedisTemplate.opsForValue().increment(key);
//        if(count == null){
//            log.warn("限流计数失败（redis异常），本次放行");
//            return true;
//        }
//
//        //3.如果是第1次，给key设60秒过期时间
//        if(count == 1){
//            stringRedisTemplate.expire(key,60, TimeUnit.SECONDS);
//        }
//        //4.判断是否超过阈值
//        if(count <= LLM_RATE_LIMIT){
//            return true;
//        }else {
//            log.warn("LLM请求超过阈值，开始限流，key={},count={}",key,count);
//            return false;
//        }
//
//    }

    //LLM限流方式二：
    //滑动窗口限流：同一会话在最近某一规定时间段内最多LLM_RATE_LIMIT次
    //固定窗口：key里带分钟，窗口窗口钉在整分钟上
    //滑动窗口：key固定，每次看现在往前推60秒，窗口跟着时间走
//    private boolean tryAcquire(Long conversationId){
//        //1.拼key：滑动窗口一个会话只有一个key
//        String key = LLM_RATE_KEY_PREFIX + ":" + conversationId;
//        long now = System.currentTimeMillis();
//        long windowStart = now - 60_000; //60秒前那一刻
//        try {
//            //2.删掉60秒前的记录
//            stringRedisTemplate.opsForZSet().removeRangeByScore(key,0,windowStart);
//
//            //3.数一数最近60秒还剩几次
//            Long count = stringRedisTemplate.opsForZSet().zCard(key);
//            long used = count == null?0:count;
//
//            //4.超过阈值-》拒绝
//            if(used >= LLM_RATE_LIMIT){
//                log.warn("LLM请求超过阈值（滑动窗口），开始限流，key ={},used={}",key,used);
//                return false;
//            }
//            //5.没超过，记录本次请求
//            stringRedisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(),now);
//
//            //6.给key续命60秒，如果没人请求，他就自动消失
//            stringRedisTemplate.expire(key,60,TimeUnit.SECONDS);
//            return true;
//        }catch (Exception e){
//            //redis出问题了，放行
//            log.warn("滑动窗口限流异常，本次放行，key={}",key,e);
//        return true;
//        }
//    }

    //LLM限流方式三：令牌桶限流
    private boolean tryAcquire(Long conversationId){
        String key = "rate:bucket:conv" + conversationId;
        try {
            Long allowed = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(key),                       //和KEYS对应
                    String.valueOf(BUCKET_CAPACITY),    //和ARGV的值对应
                    String.valueOf(BUCKET_RATE),
                    String.valueOf(System.currentTimeMillis()),
                    "1"
            );
            if(allowed == null || allowed == 0L){
                log.warn("LLM请求超过阈值（令牌桶），开始限流，key={}",key);
                return false;
            }
            return true;
        }catch (Exception e){
            log.warn("令牌桶限流异常，本次放行，key={}",key,e);
            return true;

        }



    }









    // 接口3：会话历史消息，游标分页（lastId 为空表示取最新一页）
    @GetMapping("/{conversationId}/messages")
    public Result<List<Message>> history(@PathVariable Long conversationId
            , @RequestParam(required = false) Long lastId
            , @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(conversationService.listMessage(conversationId, lastId, size));
    }

    private List<ReferenceItem> toReferenceItems(List<RetrievedChunk> refs){
        List<ReferenceItem> items = new ArrayList<>();
        for(RetrievedChunk c : refs){
        String text = c.getContent() == null ?"" : c.getContent();
        String snippet = text.length() > 100 ? text.substring(0,100) + "..." : text;
            items.add(new ReferenceItem(c.getDocumentId(),c.getDocTitle(),c.getChunkIndex(),c.getScore(),snippet));
        }
        return items;
    }



    @GetMapping(value = "/{conversationId}/stream",produces = "text/event-stream;charset=utf-8")
    public SseEmitter stream(@PathVariable Long conversationId){
        //1.设置连接超时时长
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        //2注册进注册表
        registry.register(conversationId,emitter);

        //3.立刻发一个连接成功的事件
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        }catch (IOException e){
            registry.remove(conversationId,emitter);
        }
        return emitter;
    }
    @PostMapping("/{id}/resolved")
    public Result<Void> resolved(@PathVariable Long id
            ,@RequestParam(defaultValue = "false") Boolean resolved){
        conversationService.markResolved(id,resolved);
        return Result.success();
    }

}
