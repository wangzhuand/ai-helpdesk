package com.example.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ClassName:SendMessageRequest
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/5 20:00
 * @Version 1.0
 */
@Data
public class SendMessageRequest {
    @NotBlank
    private String message;
}
