package com.example.helpdesk.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JWT 工具类：负责"签发 token"和"解析 token"两件事
 * <p>签发和解析必须用同一把密钥(secret)，密钥写在 application.yml 里</p>
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-hours}")
    private long expireHours;

    /** 用密钥生成签名钥匙（签发和验签都要用，所以单独抽出来） */
    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 token：把 userId 和 role 塞进去，设好过期时间，签名返回 */
    public String createToken(Long userId, String role) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))   // 标准字段：这个 token 代表谁
                .claim("role", role)                  // 自定义字段：角色
                .setIssuedAt(new Date())              // 签发时间
                .setExpiration(new Date(System.currentTimeMillis() + expireHours * 3600 * 1000))
                .signWith(getKey(), SignatureAlgorithm.HS256)  // 用钥匙签名
                .compact();                           // 拼成最终字符串
    }

    /** 解析 token：验签通过后取出里面的内容；被篡改或过期会抛异常 */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())              // 用同一把钥匙验签
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
