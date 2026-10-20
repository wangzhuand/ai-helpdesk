package com.example.helpdesk.service;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.helpdesk.common.BusinessException;

import com.example.helpdesk.component.RedisDelayQueue;
import com.example.helpdesk.component.SseSessionRegistry;
import com.example.helpdesk.component.SystemMessageCreatedEvent;
import com.example.helpdesk.entity.Conversation;
import com.example.helpdesk.entity.Message;
import com.example.helpdesk.entity.Ticket;
import com.example.helpdesk.entity.Visitor;
import com.example.helpdesk.mapper.ConversationMapper;
import com.example.helpdesk.mapper.MessageMapper;
import com.example.helpdesk.mapper.VisitorMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ClassName:ConversationService
 * Package:com.example.helpdesk.service
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 21:42
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationMapper conversationMapper;
    private final VisitorMapper visitorMapper;
    private final MessageMapper messageMapper;
    private final AiService aiService;
    private final TicketService ticketService;
    private final SseSessionRegistry registry;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    //锁的TTL：必须大于最慢的业务耗时，否则锁会在业务跑完前过期
    private static final int LOCK_TTL_SECONDS = 30;



    //分布式锁的释放锁的lua脚本
    private static final String UNLOCK_LUA = """
            if redis.call('GET',KEYS[1]) == ARGV[1] then
               return redis.call('DEL',KEYS[1])
            else
               return 0
            end       
            """;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(UNLOCK_LUA);
        UNLOCK_SCRIPT.setResultType(Long.class);

    }


    public Long createConversation() {
        //1.先创建一个访客
        Visitor visitor = new Visitor();
        visitorMapper.insert(visitor);
        //2.用刚刚创建的访客id创建一场会话
        Conversation conversation = new Conversation();
        conversation.setVisitorId(visitor.getId());
        conversation.setStatus("AI");
        conversationMapper.insert(conversation);

        return conversation.getId();
    }

    //接口2：发送消息
    public Message saveVisitorMessage(Long conversationId, String content) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }

        Message message1 = new Message();
        message1.setConversationId(conversationId);
        message1.setSenderType("VISITOR");
        message1.setContent(content);
        message1.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message1);

        conversation.setLastMessageAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        evictRecentCache(conversationId);

        // ★ W9-⑥ 投递延迟任务：10 分钟后检查有没有人回复
        // 注意这里直接用本类已有的 redis，而不是注入 RedisDelayQueue
        // —— 否则 RedisDelayQueue 依赖 ConversationService、ConversationService 又依赖它，形成循环依赖
        // ★ 同一个 member 再次 ZADD 是【更新 score】而不是新增一条 → 访客每说一句，计时自动重置
        try {
            redis.opsForZSet().add(
                    RedisDelayQueue.DELAY_QUEUE_KEY,
                    RedisDelayQueue.REPLY_TASK_PREFIX + conversationId,
                    System.currentTimeMillis() + RedisDelayQueue.REPLY_TIMEOUT_MILLIS);
        } catch (Exception e) {
            // 延迟检查是增强，投递失败不能影响发消息本身
            log.warn("投递延迟任务失败，conversationId={}", conversationId, e);
        }

        return message1;
    }

    //会话缓存的key--抽出来，读缓存和删缓存共用，避免两边拼错
    private String recentCacheKey(Long conversationId){
    return "chat:conv:" + conversationId + ":recent";
    }

    //删缓存：“写时删”--不更新缓存，下次读的时候自然回填
    private void evictRecentCache(Long conversationId){
        try {
            redis.delete(recentCacheKey(conversationId));
        }catch (Exception e){
            //缓存是增强不是依赖：Redis出问题也不能影响发消息本身
            log.warn("删除会话缓存失败，conversationId={}",conversationId,e);
        }
    }

    //取最近20条作为上下文
    public List<Message> recentMessages(Long conversationId) {
         String key = recentCacheKey(conversationId);
         log.info("缓存进入recentMessages,conversationId={},key={}",conversationId,key);


         //1.先查缓存
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null){
                return objectMapper.readValue(cached, new TypeReference<List<Message>>() {
                });
            }
        }catch (Exception e){
            log.warn("读会话缓存失败，降级查库，conversationId={}",conversationId,e);
        }

        //2.缓存没命中-》查数据库
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId,conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT 20");
        List<Message> recent = messageMapper.selectList(wrapper);
        Collections.reverse(recent);//转为正序

        //3.回填缓存（TTL：30分钟：兜底，万一某次漏删也能自动恢复）
        try {
            redis.opsForValue().set(key,objectMapper.writeValueAsString(recent),30, TimeUnit.MINUTES);
            log.info("缓存回填成功，key={}",key);
        }catch (Exception e){
            log.warn("回填会话缓存失败，conversationId={}",conversationId,e);
        }
        return recent;

    }

    public Message saveAiMessage(Long conversationId, String content) {
        Message aiMsg = new Message();
        aiMsg.setConversationId(conversationId);
        aiMsg.setSenderType("AI");
        aiMsg.setContent(content);
        aiMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(aiMsg);
        evictRecentCache(conversationId);
        return aiMsg;
    }

    //接口3：会话分页
    public List<Message> listMessage(Long conversationId, Long lastId, Integer size) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        //限制size的大小，如果超过50就按50算
        if (size == null || size <= 0) {
            size = 20;
        }
        if (size > 50) {
            size = 50;
        }
        LambdaQueryWrapper<Message> queryWrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + size);

        if (lastId != null) {
            queryWrapper.lt(Message::getId, lastId);
        }
        return messageMapper.selectList(queryWrapper);

    }



    //往会话里插一条系统消息
    public Message saveSystemMessage(Long conversationId,String content){
        Message msg = new Message();
        msg.setConversationId(conversationId);
        msg.setSenderType("SYSTEM");
        msg.setContent(content);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);


        //更新会话最后的活跃时间
        Conversation conversation = conversationMapper.selectById(conversationId);
        if(conversation != null){
            conversation.setLastMessageAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
        //推送给访客--发事件，由SystemMessagePusher决定时机
        //调用方有事务，事务提交后再推，没有事务--立刻推
        //（不能在事务里直接push，那样推送会早于提交，访客可能看到但库里查不到）
        eventPublisher.publishEvent(new SystemMessageCreatedEvent(conversationId,msg));
        evictRecentCache(conversationId);
        return msg;

    }




    //把会话传给人工
    public void escalateToAgent(Long conversationId){
        Conversation conversation = conversationMapper.selectById(conversationId);
        if(conversation == null){
            throw new BusinessException("会话不存在");
        }
        if(!"AI".equals(conversation.getStatus())){
            return;
        }
        conversation.setStatus("AGENT");
        conversationMapper.updateById(conversation);

    }




    //这个会话是否被人工接管了
    public boolean isHumanMode(Long conversationId){
        Conversation conversation = conversationMapper.selectById(conversationId);
        if(conversation == null){
            throw new BusinessException("会话不存在");
        }
        return "AGENT".equals(conversation.getStatus());
    }


    //控制台会话列表，mine = true 看我的，= false 看待接管池
    public List<Conversation> listForConsole(Boolean mine,Long agentId){
        LambdaQueryWrapper<Conversation> conversationLambdaQueryWrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getStatus,"AGENT")
                .orderByDesc(Conversation::getLastMessageAt);
        if (Boolean.TRUE.equals(mine)){
            conversationLambdaQueryWrapper.eq(Conversation::getAgentId,agentId);
        }else {
            conversationLambdaQueryWrapper.isNull(Conversation::getAgentId);
        }
        return  conversationMapper.selectList(conversationLambdaQueryWrapper);
    }


    public void takeOver(Long id, Long userId) {

        int affected = conversationMapper.update(null,new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId,id)
                        .eq(Conversation::getStatus,"AGENT")
                        .isNull(Conversation::getAgentId)//只有没人接的时候才能接
                .set(Conversation::getAgentId,userId)
        );
        if (affected == 0){
            throw new BusinessException("会话已被其他坐席接管，请刷新");
        }
        //让访客知道坐席介入了
        saveSystemMessage(id,"客服已接入，正在为您服务");

    }

    public Message saveAgentMessage(Long id, String message,Long agentId) {
        //越权校验：只有负责这个会话的坐席才能回复
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null){
            throw new BusinessException("会话不存在");
        }
        //Objects.equals处理null安全，会话还没被接管时（agentId为null）时，谁都不能发
        if (!Objects.equals(conversation.getAgentId(),agentId)){
            throw new BusinessException("你未接管该会话，无法回复");
        }



        Message agentMsg = new Message();
        agentMsg.setConversationId(id);
        agentMsg.setContent(message);
        agentMsg.setSenderType("AGENT");
        agentMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(agentMsg);



        //更新会话的最后活跃时间
        if(conversation != null){
            conversation.setLastMessageAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }

        evictRecentCache(id);

        return agentMsg;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markResolved(Long id, Boolean resolved) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null){
            throw new BusinessException("会话不存在");
        }
        //原子幂等：只有还没打过才更新得到（和takeover同一个套路）
        int affected = conversationMapper.update(null,new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId,id)
                .isNull(Conversation::getResolved)
                .set(Conversation::getResolved,Boolean.TRUE.equals(resolved)?1:0));
        if (affected == 0){
            return; //已经答过了-》直接返回，不重复流转工单
        }

        //只有已解决才动工单，会话状态两种回答都不动
        if (Boolean.TRUE.equals(resolved)){
            Ticket ticket = ticketService.findOpenByConversation(id);
            if (ticket != null){
                //状态机不允许OPEN直接跳RESOLVED,所以按合法路径分两步走
                //（operatorId 传null:这是系统/访客触发的，不是坐席，和TicketTools.create的约定一致）
                if (TicketStatus.OPEN.name().equals(ticket.getStatus())){
                    ticketService.transition(ticket.getId(),TicketStatus.PROCESSING,null,"访客确认已解决，系统自动受理");
                }
                ticketService.transition(ticket.getId(),TicketStatus.RESOLVED,null,"访客确认已解决");
            }
            saveSystemMessage(id,"感谢您的反馈，本会话已结束");
        }else {
            //未解决
            saveSystemMessage(id,"收到，我们会继续为您跟进");
        }
    }


    public void takeOverByRedisLock(Long id,Long userId){
        String lockKey = "lock:takeover" + id;
        String lockValue = UUID.randomUUID().toString();

        //1.占锁
        Boolean got = redis.opsForValue().setIfAbsent(lockKey,lockValue,LOCK_TTL_SECONDS,TimeUnit.SECONDS);
        if(!Boolean.TRUE.equals(got)){
            throw new BusinessException("正在被其他坐席接管，请稍后再试");
        }


        try {
            //2.业务
            Conversation conversation = conversationMapper.selectById(id);
            if (conversation == null){
                throw new BusinessException("会话不存在");
            }
            conversationMapper.update(null,new LambdaUpdateWrapper<Conversation>()
                    .eq(Conversation::getId,id)
                    .set(Conversation::getAgentId,userId));
            saveSystemMessage(id,"客服已接入，正在为你服务");
        }finally {
            //3.释放锁（Lua校验value再删）
            try {
                redis.execute(UNLOCK_SCRIPT,List.of(lockKey),lockValue);
            }catch (Exception e){
                log.warn("释放抢单锁失败吗，lockKey={}",lockKey,e);
            }

        }





    }






}