package com.example.helpdesk.dto;

import com.example.helpdesk.entity.Message;
import lombok.Data;

/**
 * ClassName:SendMessageResponse
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/7 14:52
 * @Version 1.0
 */
@Data
public class SendMessageResponse {
private Message visitorMsg;
private Message aiMsg;
}
