package com.example.ssds.api.dto;

/**
 * FR-02 儀表板熱度來源狀態列。
 *
 * @param sourceCode   來源代碼（THREADS / GOOGLE_TRENDS / INSTAGRAM / MANUAL）
 * @param availability 可用性狀態（AVAILABLE / DEGRADED / UNAVAILABLE）
 * @param enabled      是否啟用
 */
public record HeatSourceDto(
        String sourceCode,
        String availability,
        Boolean enabled) {
}