package com.example.ssds.util;




import java.io.IOException;



import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.core.userdetails.UserDetails;

import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import org.springframework.stereotype.Component;

import org.springframework.util.StringUtils;

import org.springframework.web.filter.OncePerRequestFilter;



import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;

import jakarta.servlet.ServletException;

import jakarta.servlet.http.HttpServletRequest;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;



@Component

@RequiredArgsConstructor

public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtUtils jwtUtil;

    private final UserDetailsServiceImpl userDetailsService;



	@Override

	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)

			throws ServletException, IOException {

		String jwt = parseJwt(request); // 1. 取出 JWT

		String username = null;



		if (jwt != null) {

			try {

				username = jwtUtil.getUserNameFromJwtToken(jwt); // 2. 解析

			} catch (JwtException e) {

				// Token 過期、簽名不對、格式壞掉 → 當作「沒帶 Token」處理

				username = null; // 不要往外拋，否則會變成 500 而不是 401

			}

		}

		if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {



			UserDetails userDetails = userDetailsService.loadUserByUsername(username); // 3. 載入



			// 再次驗證 Token 簽名與是否過期，確保沒被竄改

			if (jwtUtil.validateToken(jwt)) {

				UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(

						userDetails, null, userDetails.getAuthorities());

				authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

				SecurityContextHolder.getContext().setAuthentication(authentication); // 4. 登記身分

			}

		}

		filterChain.doFilter(request, response); // 放行



	}



	private String parseJwt(HttpServletRequest request) {

		String headerAuth = request.getHeader("Authorization");

		if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {

			String token = headerAuth.substring(7); // 去掉 "Bearer "

			if (StringUtils.hasText(token)) {

				return token;

			}

		}

		return null;

	}



}
