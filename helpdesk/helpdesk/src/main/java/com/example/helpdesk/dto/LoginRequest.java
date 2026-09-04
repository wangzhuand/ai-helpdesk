package com.example.helpdesk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ClassName:LoginRequest
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 18:49
 * @Version 1.0
 */
@Data
public class LoginRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;



}
