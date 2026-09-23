package com.example.helpdesk.controller;

import com.example.helpdesk.common.Result;
import com.example.helpdesk.common.UserContext;
import com.example.helpdesk.dto.TicketTransitionRequest;
import com.example.helpdesk.dto.WorkOrder;
import com.example.helpdesk.entity.Ticket;
import com.example.helpdesk.entity.TicketLog;
import com.example.helpdesk.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

 import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ClassName:TicketController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/22 16:39
 * @Version 1.0
 */
@RestController
@RequestMapping("/api/console/tickets")
@RequiredArgsConstructor
public class TicketController {
    private final TicketService ticketService;


    //建表单
    @PostMapping
    public Result<Long> create(@Valid @RequestBody WorkOrder workOrder){
        return  Result.success(
                ticketService.create(workOrder.getTitle(),workOrder.getCategory()
                ,workOrder.getDescription(),workOrder.getConversationId(), UserContext.getUserId())
        );
    }

    @GetMapping
    public Result<Page<Ticket>> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false)Integer priority,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "10")int size){
        return Result.success(  ticketService.page(status,priority,page,size));
    }

    @GetMapping("/{id}")
    public Result<Ticket> detail(@PathVariable Long id){
        return Result.success(ticketService.getById(id));
    }

    @PostMapping("/{id}/takeover")
    public Result<Void> takeOver(@PathVariable Long id){
        ticketService.takeOver(id,UserContext.getUserId());//接管人=当前人工客服
        return Result.success();
    }

    @PostMapping("/{id}/transition")
    public Result<Ticket> transition(@PathVariable Long id,@Valid @RequestBody TicketTransitionRequest request){
        ticketService.transition(id,request.toStatus(),
                UserContext.getUserId(),request.getRemark());
    return Result.success();
    }

    @GetMapping("/{id}/logs")
    public Result<List<TicketLog>> logs(@PathVariable Long id){
        return Result.success(ticketService.logs(id));
    }




}
