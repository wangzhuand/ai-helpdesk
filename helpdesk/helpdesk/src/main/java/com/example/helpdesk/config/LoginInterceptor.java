package com.example.helpdesk.config;

import com.example.helpdesk.common.JwtUtil;
import com.example.helpdesk.common.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * ClassName:LoginInterceptor
 * Package:com.example.helpdesk.config
 * Description:
 *
 * @Author 妄汐霜
 * @Create 2026/9/6 15:28
 * @Version 1.0
 */
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception{
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())){
            return true;
        }
        //拿请求的token
        String token = request.getHeader("Authorization");
        if(token == null || !token.startsWith("Bearer ")){
            writeUnauthorized(response,"未登录");
            return  false;
        }
        token = token.substring(7);// 去掉 "Bearer " 前缀


        //检验拿到的token
        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get("role",String.class);
            UserContext.set(userId, role);//检验通过，把token放进UserContext
        }catch (Exception e){
            writeUnauthorized(response,"登录状态异常，请重新登录");
            return false;
        }
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");

    }
}
