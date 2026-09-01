package com.example.ssds.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Component;
import com.example.ssds.infra.entity.AppUser;
import java.util.Date;

@Component
public class JwtUtils {

    
    public static final String SECRET_KEY = "SSDS_SUPER_SECRET_KEY_BACKEND_ONLY";

   
    public String generateToken(AppUser user) {
        long currentTimeMillis = System.currentTimeMillis();
        long expireTimeMillis = currentTimeMillis + (2 * 60 * 60 * 1000); // 2 小時 = 2 * 60 * 1000 毫秒

        return JWT.create()
                .withSubject(user.getEmail()) 
                .withIssuedAt(new Date(currentTimeMillis)) 
                .withExpiresAt(new Date(expireTimeMillis))
                .withClaim("msg", "USER") 
                .sign(Algorithm.HMAC256(SECRET_KEY)); 
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