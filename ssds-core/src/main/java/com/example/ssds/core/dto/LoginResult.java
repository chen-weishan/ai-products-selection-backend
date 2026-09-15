package com.example.ssds.core.dto;

/** FR-01 登入成功的回應：access + refresh token 與使用者基本資料。 */
public record LoginResult(TokenPair tokens, AuthenticatedUser user) {
}