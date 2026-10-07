package com.example.helpdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.helpdesk.common.BusinessException;

import com.example.helpdesk.component.TicketCreatedEvent;
import com.example.helpdesk.entity.Ticket;
import com.example.helpdesk.entity.TicketLog;
import com.example.helpdesk.mapper.TicketLogMapper;
import com.example.helpdesk.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ClassName:TicketService
 * Package:com.example.helpdesk.service
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/21 20:45
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {
    private final TicketMapper ticketMapper;
    private final TicketLogMapper ticketLogMapper;
    //SLA检查的Topic --生产者在这里定义，消费者引用它
    public static final String SLA_TOPIC = "sla-check";

    //SLA时长（分钟）：工单创建之后多久算超时
    public static final int SLA_MINUTES = 1;

    //延迟等级：5=1分钟
    public static final int SLA_DELAY_LEVEL = 5;

    //Spring内置的事件发布器（ApplicationConText自己已经实现了这个接口，无需再配置）
    private final ApplicationEventPublisher eventPublisher;


    //流转工单状态，校验合法--》改--》乐观锁--》写日志
    @Transactional(rollbackFor = Exception.class)
    public void transition(Long ticketId,TicketStatus target,Long operatorId,String remark){
        Ticket ticket = ticketMapper.selectById(ticketId);
        if(ticket == null){
        throw new BusinessException("工单不存在");
        }
        TicketStatus current = TicketStatus.valueOf(ticket.getStatus());

        //状态机校验
        if(!current.canTransitionTo(target)){
            throw new BusinessException(
                    "工单状态不允许从" + current + "流转到" + target);
        }
        //3.改状态，updateById 自带乐观锁（@Version生效）
        ticket.setStatus(target.name());
        int affected = ticketMapper.updateById(ticket);
        if(affected == 0){
            throw new BusinessException("工单已被他人修改，请刷新后重试");
        }

        //4.写日志
        TicketLog ticketLog = new TicketLog();
        ticketLog.setTicketId(ticketId);
        ticketLog.setOperatorId(operatorId);
        ticketLog.setAction(target.name());
        ticketLog.setFromStatus(current.name());
        ticketLog.setToStatus(target.name());
        ticketLog.setRemark(remark);
        ticketLog.setCreatedAt(LocalDateTime.now());
        ticketLogMapper.insert(ticketLog);
    }



    //生成对外单号
    private String generateTicketNo(){
        return "T" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    //1.创建工单
    @Transactional(rollbackFor = Exception.class)
    public Long create(String title,String category,String description,Long conversationId,Long createdBy){
        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setTitle(title);
        ticket.setCategory(category);
        ticket.setDescription(description);
        ticket.setConversationId(conversationId);
        ticket.setStatus(TicketStatus.OPEN.name());
        ticket.setSlaDeadline(LocalDateTime.now().plusMinutes(SLA_MINUTES));
        ticket.setPriority(1);
        ticketMapper.insert(ticket);

        //写一条CREATE日志，让工单完整
        TicketLog ticketLog = new TicketLog();
        ticketLog.setTicketId(ticket.getId());
        ticketLog.setOperatorId(createdBy);
        ticketLog.setAction("CREATE");
        ticketLog.setToStatus(TicketStatus.OPEN.name());
        ticketLog.setRemark("创建工单");
        ticketLog.setCreatedAt(LocalDateTime.now());
        ticketLogMapper.insert(ticketLog);
        eventPublisher.publishEvent(new TicketCreatedEvent(ticket.getId()));

        return ticket.getId();
    }

    /*
     * SLA 超时自动升级：把工单优先级提到 3。
     *
     * ★ 幂等的关键全在这一条 UPDATE 的 WHERE 里：
     *   ① status IN (三个"还开着"的状态) —— 已解决/已关闭的不动
     *   ② priority < 3                      —— 已经升级过的不重复升
     *   两条一起，天然幂等：重复执行结果一样，也不会重复写日志。
     *
     * ★ 为什么用"赋值 priority = 3"而不是"自增"：
     *   自增的话重复消费会变成 4、5、6…… 赋值才幂等。
     *
     * 这里用 update(null, wrapper)，@Version 乐观锁【不会】生效
     *    （它只在 updateById(实体) 时带版本条件）。防并发靠的就是上面那两条 WHERE。
     *
     * @return true = 真的升级了；false = 被 WHERE 挡住（已解决 / 已升级 / 工单不存在）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean escalateBySla(Long ticketId){
        int affected = ticketMapper.update(null,new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getId,ticketId)
                .in(Ticket::getStatus,List.of(
                        TicketStatus.OPEN.name(),
                        TicketStatus.PROCESSING.name(),
                        TicketStatus.REOPENED.name()
                ))
                .lt(Ticket::getPriority,3)
                .set(Ticket::getPriority,3));

        if (affected == 0){
            return false;
        }

        //写一条日志，让坐席在时间线上看到系统自动升级
        Ticket ticket = ticketMapper.selectById(ticketId);
        TicketLog ticketLog = new TicketLog();
        ticketLog.setTicketId(ticketId);
        ticketLog.setOperatorId(null);  //系统自动，没有操作人
        ticketLog.setAction("SLA_ESCALATE");
        ticketLog.setFromStatus(ticket.getStatus());
        ticketLog.setToStatus(ticket.getStatus());
        ticketLog.setRemark("超时未解决，系统自动升级优先级");
        ticketLog.setCreatedAt(LocalDateTime.now());
        ticketLogMapper.insert(ticketLog);
        return true;
    }











    //查单
    public Ticket getById(Long id){
        return ticketMapper.selectById(id);
    }

    //列表
    public List<Ticket> list(){
        return ticketMapper.selectList(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void takeOver(Long id,Long agentId){
        Ticket ticket = ticketMapper.selectById(id);
        if(ticket == null){
            throw new BusinessException("工单不存在");
        }
        if(ticket.getAssigneeId() != null){
            throw new BusinessException("工单已指派给其他处理人");
        }

        TicketStatus current = TicketStatus.valueOf(ticket.getStatus());
        if(!current.canTransitionTo(TicketStatus.PROCESSING)){
            throw new BusinessException("当前状态为：("+current+")无法接管");
        }

        ticket.setAssigneeId(agentId);
        ticket.setStatus(TicketStatus.PROCESSING.name());

        int affected = ticketMapper.updateById(ticket);
        if (affected == 0) {
            throw new BusinessException("工单已被他人接管，请刷新");
        }
        //写日志
        TicketLog ticketLog = new TicketLog();
        ticketLog.setAction("ASSIGN");
        ticketLog.setTicketId(id);
        ticketLog.setOperatorId(agentId);
        ticketLog.setFromStatus(current.name());
        ticketLog.setToStatus(TicketStatus.PROCESSING.name());
        ticketLog.setRemark("接管工单");
        ticketLog.setCreatedAt(LocalDateTime.now());
        ticketLogMapper.insert(ticketLog);
        log.info("工单接管成功，工单ID：{},接管人：{}",id,agentId);
    }


    public Page<Ticket> page(String status,Integer priority,int page,int size){
        Page<Ticket> p = new Page<>(page,size);
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(status != null && !status.isBlank(),Ticket::getStatus,status)
                .eq(priority != null,Ticket::getPriority,priority)
                .orderByDesc(Ticket::getId);
        return ticketMapper.selectPage(p,wrapper);
    }

    public List<TicketLog> logs(Long ticketId){
        return ticketLogMapper.selectList(new LambdaQueryWrapper<TicketLog>()
                .eq(TicketLog::getTicketId,ticketId)
                .orderByAsc(TicketLog::getId)
        );
    }


    //查询是否有未关闭的工单，用于建单幂等兜底
    public Ticket findOpenByConversation(Long conversationId){
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getConversationId,conversationId)
                .in(Ticket::getStatus,List.of(
                        TicketStatus.OPEN.name(),
                        TicketStatus.PROCESSING.name(),
                        TicketStatus.REOPENED.name()))
                .orderByDesc(Ticket::getId)
                .last("LIMIT 1"));
    }







}
