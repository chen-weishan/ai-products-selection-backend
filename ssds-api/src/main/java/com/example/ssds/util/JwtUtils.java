package com.example.ssds.util;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.ssds.infra.entity.AppUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtils {

    public static final String SECRET_KEY = "SSDS_SUPER_SECRET_KEY_BACKEND_ONLY_2026";

    public String generateToken(AppUser user) {
        long currentTimeMillis = System.currentTimeMillis();
        long expireTimeMillis = currentTimeMillis + (2 * 60 * 60 * 1000); // 2 小時過期

        List<String> rolesStrList = user.getRoles().stream()
                .map(role -> role.getCode().name())
                .collect(Collectors.toList());

        return JWT.create()
                .withSubject(user.getEmail())
                .withIssuedAt(new Date(currentTimeMillis))
                .withExpiresAt(new Date(expireTimeMillis))
                .withClaim("roles", rolesStrList) 
                .sign(Algorithm.HMAC256(SECRET_KEY));
    }
   
    // 解析 Token，驗證簽名，取出 Payload 裡的所有 Claims

    	// 簽名不對或已過期時，parseSignedClaims() 會拋出 JwtException，交給呼叫端 (Filter) 處理

    	public Claims extractAllClaims(String token) {

    		return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();

    	}



    	// 從 Token 取出帳號 (Email)

    	public String getUserNameFromJwtToken(String token) {

    		return extractAllClaims(token).getSubject();

    	}

 
    // jwt.secret 是 Base64 編碼過的亂數，要先解碼還原成位元組

    	private SecretKey getSigningKey() {

    		byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);

    		return Keys.hmacShaKeyFor(keyBytes);

    	}
    public boolean validateToken(String token) {
        try {
            DecodedJWT jwt = JWT.require(Algorithm.HMAC256(SECRET_KEY))
                    .build()
                    .verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
        
    }
}