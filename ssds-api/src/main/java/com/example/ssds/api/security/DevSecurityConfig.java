package com.example.ssds.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 僅供本機開發與 Postman 測試使用的 Security 設定。
 *
 * <p>正式環境不會載入此設定；FR-01 完成後應由 JWT SecurityConfig 取代。
 */
@Configuration
@Profile("dev")
@EnableMethodSecurity
public class DevSecurityConfig {

    @Bean
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 開發環境以 HTTP Basic 測 REST API，不使用瀏覽器 Cookie Session。
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    UserDetailsService devUserDetailsService(
            @Value("${ssds.security.dev.username}") String username,
            @Value("${ssds.security.dev.password}") String password,
            PasswordEncoder passwordEncoder
    ) {
        UserDetails buyer = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("BUYER")
                .build();
        return new InMemoryUserDetailsManager(buyer);
    }

    @Bean
    PasswordEncoder devPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
