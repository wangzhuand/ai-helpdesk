package com.example.helpdesk.controller;

import com.example.helpdesk.Result;
import com.example.helpdesk.dto.LoginRequest;
import com.example.helpdesk.dto.LoginResponse;
import com.example.helpdesk.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ClassName:AuthController
 * Package:com.example.helpdesk.controller
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/4 20:09
 * @Version 1.0
 */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest){
        return Result.success(authService.login(loginRequest));
    }

}
