package com.example.ssds.core.dto;

/** access／refresh token 一組。用於登入與換發（§FR-01 token 換發流程）。 */
public record TokenPair(String accessToken, String refreshToken) {
}