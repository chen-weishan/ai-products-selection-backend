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

 
    // 修正：SECRET_KEY 不是 Base64 編碼，是一般字串；且 generateToken() 用 auth0
    // 的 Algorithm.HMAC256(SECRET_KEY) 簽章時，內部也是直接取字串的 UTF-8 位元組。
    // 這裡改成同樣用 UTF-8 位元組，兩邊才會是同一把金鑰，簽章跟驗證才對得起來。
    // （原本誤把它當 Base64 解碼，解出來的位元組跟簽章時用的完全不同，
    //   而且 SECRET_KEY 含有底線字元，Decoders.BASE64.decode 甚至會直接拋出
    //   IllegalArgumentException，導致每次驗證 token 都 500。）

    	private SecretKey getSigningKey() {

    		byte[] keyBytes = SECRET_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8);

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