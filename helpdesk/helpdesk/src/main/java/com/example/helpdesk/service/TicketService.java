package com.example.helpdesk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.helpdesk.common.BusinessException;

import com.example.helpdesk.entity.Ticket;
import com.example.helpdesk.entity.TicketLog;
import com.example.helpdesk.mapper.TicketLogMapper;
import com.example.helpdesk.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
        return ticket.getId();
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





}
