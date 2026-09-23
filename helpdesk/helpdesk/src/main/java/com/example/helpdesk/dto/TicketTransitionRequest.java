package com.example.helpdesk.dto;

import com.example.helpdesk.common.BusinessException;
import com.example.helpdesk.service.TicketStatus;
import lombok.Data;

/**
 * ClassName:TicketTransitionRequest
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/22 17:00
 * @Version 1.0
 */
@Data
public class TicketTransitionRequest {
    private Long ticketId;
    private String status;
    private String remark;

    public TicketStatus toStatus(){
        if(status == null || status.isBlank()){
            throw new BusinessException("目标状态不能为空");
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase());
        }catch (IllegalArgumentException e){
            throw  new BusinessException("非法的目标状态" + status);
        }
    }

}
