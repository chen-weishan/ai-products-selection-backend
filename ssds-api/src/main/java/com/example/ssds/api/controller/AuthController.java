package com.example.ssds.api.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.dto.LoginRequest;
import com.example.ssds.api.dto.RefreshRequest;
import com.example.ssds.api.security.AuthService;
import com.example.ssds.core.dto.AuthenticatedUser;
import com.example.ssds.core.dto.LoginResult;
import com.example.ssds.core.dto.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-01 登入與權限、§8.2 /auth/* 端點。 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        LoginResult result = authService.login(
                request.email(), request.password(), http.getHeader("User-Agent"), http.getRemoteAddr());
        return ApiResponse.success(result);
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        TokenPair tokens = authService.refresh(
                request.refreshToken(), http.getHeader("User-Agent"), http.getRemoteAddr());
        return ApiResponse.success(tokens);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthenticatedUser> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(authService.currentUser(userId));
    }
}