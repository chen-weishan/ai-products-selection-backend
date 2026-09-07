package com.example.ssds.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * access token（JWT）簽發與解析。
 *
 * <p>refresh token 刻意不是 JWT —— 它必須可被個別撤銷（規格書 §7.2.1
 * refresh_token.revoked_at），JWT 本身無狀態、簽出去就管不了，
 * 因此 refresh token 是隨機字串＋雜湊存表，見 {@code AuthService}。
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_DISPLAY_NAME = "displayName";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtTokenProvider(
            @Value("${ssds.jwt.secret}") String secret,
            @Value("${ssds.jwt.access-ttl-minutes:120}") long accessTtlMinutes) {
        // HS256 要求 key 至少 256 bit；secret 太短時這裡會直接啟動失敗，好過簽出弱金鑰
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    public String generateAccessToken(Long userId, String email, String displayName, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_DISPLAY_NAME, displayName)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** 解析並驗證簽章／效期。過期拋 {@link ExpiredJwtException}，其餘不合法拋 {@link JwtException}。 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static Long userId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    @SuppressWarnings("unchecked")
    public static List<String> roles(Claims claims) {
        return (List<String>) claims.get(CLAIM_ROLES, List.class);
    }

    public static String email(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }

    public static String displayName(Claims claims) {
        return claims.get(CLAIM_DISPLAY_NAME, String.class);
    }
}