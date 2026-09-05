package com.example.ssds.api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 開發環境的臨時安全設定（FR-01 完成後整個檔案刪除）。
 *
 * <p>
 * 只做一件事：關閉 CSRF。Spring Security 預設對 POST／PUT／DELETE 要求 CSRF token，
 * 沒帶會在進入 Controller 之前被擋掉。因為 {@code CsrfFilter} 排在 Basic 認證之前，
 * 此時請求還是匿名狀態，回的是 401 而不是直覺上的 403。
 *
 * <p>
 * 本專案是純 REST API：認證走 HTTP Basic、請求體是 JSON，不存在「瀏覽器自動夾帶
 * cookie 送出跨站表單」這個攻擊面，CSRF token 沒有保護對象。
 *
 * <p>
 * {@code @Profile("dev")} 限定只在開發環境生效，prod 維持 Spring Security 預設。
 */
@Configuration
@EnableWebSecurity
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
