package com.example.ssds.api.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
    SecurityFilterChain devSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource devCorsConfigurationSource
    ) throws Exception {
        return http
                // 開發環境以 HTTP Basic 測 REST API，不使用瀏覽器 Cookie Session。
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(devCorsConfigurationSource))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
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
    CorsConfigurationSource devCorsConfigurationSource(
            @Value("${ssds.cors.allowed-origins}") String allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    UserDetailsService devUserDetailsService(
            @Value("${ssds.security.dev.username}") String username,
            @Value("${ssds.security.dev.password}") String password,
            PasswordEncoder passwordEncoder
    ) {
        UserDetails sysAdmin = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("SYS_ADMIN")
                .build();
        return new InMemoryUserDetailsManager(sysAdmin);
    }

    @Bean
    PasswordEncoder devPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
