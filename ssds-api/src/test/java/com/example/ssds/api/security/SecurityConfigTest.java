package com.example.ssds.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 安全設定的組裝結果（{@link SecurityConfig}）。
 *
 * <p><b>取代 {@code DevSecurityIntegrationTest}</b>：那支驗的是 {@code DevSecurityConfig}
 * 的 HTTP Basic 行為，而 PR #11 已將整個 {@code DevSecurityConfig} 刪除、改用 JWT。
 * 被測對象不存在了，那支測試只會恆紅，因此移除並改寫成本檔。
 *
 * <p>保留的是兩項與認證機制無關、換成 JWT 之後依然成立的性質：
 * <ul>
 *   <li>整個應用只有<b>一條</b> SecurityFilterChain——歷史上 dev 與 common.config
 *       兩處各有一份設定，兩條 chain 並存時哪一條生效取決於 bean 順序，很難察覺</li>
 *   <li>CORS 放行前端來源——前端跨埠呼叫失敗時症狀是瀏覽器端的 CORS 錯誤，
 *       後端日誌什麼都看不到，值得用測試釘住</li>
 * </ul>
 *
 * <p>改用 {@code @SpringBootTest} 而非原本的手動 {@code AnnotationConfigWebApplicationContext}：
 * {@link SecurityConfig} 建構子注入 {@code JwtAuthFilter}，而它又依賴 {@code JwtUtils} 與
 * {@code UserDetailsServiceImpl}（後者要 JPA repository）。手動 context 得為這一串全部造假，
 * 造出來的東西與正式啟動時的組裝方式已經不同，測不到真正想測的「組裝結果」。
 *
 * <p>Flyway 護欄的理由見 {@code build.gradle} 的 test 設定。
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
@SpringBootTest
class SecurityConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UrlBasedCorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("整個應用只註冊一條 SecurityFilterChain")
    void exactlyOneSecurityFilterChain() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }

    @Test
    @DisplayName("CORS 放行前端來源，且允許帶 Authorization 標頭")
    void corsAllowsFrontendOrigin() {
        CorsConfiguration config = corsConfigurationSource.getCorsConfigurations().get("/**");

        assertThat(config).as("未對 /** 註冊 CORS 設定").isNotNull();
        assertThat(config.getAllowedOrigins()).contains("http://localhost:4200");
        // JWT 走 Authorization 標頭，沒放行的話前端帶 token 的請求會被 preflight 擋掉
        assertThat(config.getAllowedHeaders()).contains("Authorization");
        assertThat(config.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
