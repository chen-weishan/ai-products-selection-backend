package com.example.ssds.api.common.config;

import java.util.Arrays;


import org.springframework.context.annotation.Bean;

import org.springframework.context.annotation.Configuration;

import org.springframework.security.authentication.AuthenticationManager;

import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.web.SecurityFilterChain;

import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;

import org.springframework.web.cors.CorsConfigurationSource;

import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.example.ssds.util.JwtAuthFilter;

import lombok.RequiredArgsConstructor;

/**
 * 開發環境的臨時安全設定（FR-01 完成後整個檔案刪除）。
 *
 * <p>
 * 只做一件事：關閉 CSRF。Spring Security 預設對 POST／PUT／DELETE 要求 CSRF token， 沒帶會在進入
 * Controller 之前被擋掉。因為 {@code CsrfFilter} 排在 Basic 認證之前， 此時請求還是匿名狀態，回的是 401
 * 而不是直覺上的 403。
 *
 * <p>
 * 本專案是純 REST API：認證走 HTTP Basic、請求體是 JSON，不存在「瀏覽器自動夾帶 cookie 送出跨站表單」這個攻擊面，CSRF
 * token 沒有保護對象。
 *
 * <p>
 * {@code @Profile("dev")} 限定只在開發環境生效，prod 維持 Spring Security 預設。
 */
@Configuration

@EnableWebSecurity

@RequiredArgsConstructor

public class SecurityConfig {



	private final JwtAuthFilter jwtAuthFilter;



	@Bean

	public PasswordEncoder passwordEncoder() {

		return new BCryptPasswordEncoder();

	}



	@Bean

	public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {

		return authConfig.getAuthenticationManager();

	}



	@Bean

	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

		http.csrf(csrf -> csrf.disable()) // 關閉 CSRF

				.cors(cors -> cors.configurationSource(corsConfigurationSource())) // 開啟跨域

				.sessionManagement(session -> // 支援確認頁暫存

				session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()); // 開發環境全公開



		http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class); // 掛上 JWT

																											// filter

		return http.build();

	}



	@Bean

	public CorsConfigurationSource corsConfigurationSource() {

		CorsConfiguration config = new CorsConfiguration();

		config.setAllowedOrigins(Arrays.asList("http://localhost:4200", "http://localhost:64567")); // 前端網址

		config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

		config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept"));

		config.setAllowCredentials(true); // 必須開啟以支援 Session Cookie



		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

		source.registerCorsConfiguration("/**", config);

		return source;

	}

}