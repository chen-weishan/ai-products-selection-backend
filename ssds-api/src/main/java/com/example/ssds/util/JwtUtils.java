package com.example.ssds.util;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.ssds.infra.entity.AppUser;

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