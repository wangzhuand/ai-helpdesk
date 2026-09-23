package com.example.helpdesk.dto;

import lombok.Data;

/**
 * ClassName:WorkOrder
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/21 21:13
 * @Version 1.0
 */
@Data
public class WorkOrder {
private String title;
private String category;
private String description;
private Long conversationId;

}
