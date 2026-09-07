package com.example.ssds.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 從 {@code Authorization: Bearer <token>} 解出 access token 並設定 SecurityContext。
 *
 * <p>驗證失敗（含過期）刻意不在這裡直接回應——只標記請求屬性，讓下游的
 * {@link RestAuthenticationEntryPoint} 決定要回 {@code TOKEN_EXPIRED} 還是
 * {@code UNAUTHORIZED}（規格書 §FR-01 token 換發流程需要區分這兩種情況）。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** {@link RestAuthenticationEntryPoint} 讀取此屬性以判斷是否為過期而非其他無效原因。 */
    public static final String ATTR_TOKEN_EXPIRED = "ssds.jwt.expired";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                Claims claims = jwtTokenProvider.parse(token);
                List<GrantedAuthority> authorities = JwtTokenProvider.roles(claims).stream()
                        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        JwtTokenProvider.userId(claims), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ExpiredJwtException e) {
                request.setAttribute(ATTR_TOKEN_EXPIRED, Boolean.TRUE);
            } catch (JwtException | IllegalArgumentException e) {
                // 簽章不符、格式錯誤等——留給 entry point 當一般未登入處理，不在此回應
            }
        }

        filterChain.doFilter(request, response);
    }
}