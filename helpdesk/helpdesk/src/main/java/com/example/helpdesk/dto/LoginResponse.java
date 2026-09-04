package com.example.helpdesk.dto;

import lombok.Data;

/**
 * ClassName:LoginResponse
 * Package:com.example.helpdesk.dto
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 18:52
 * @Version 1.0
 */
@Data
public class LoginResponse {
    private String token;
    private String nickname;
    private String role;
}
