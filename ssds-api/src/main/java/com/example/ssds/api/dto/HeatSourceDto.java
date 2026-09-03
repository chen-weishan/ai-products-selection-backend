package com.example.ssds.api.dto;

import java.time.OffsetDateTime;

/**
 * FR-02 儀表板熱度來源狀態列。
 *
 * <p>FR-02 要求顯示「各來源名稱、最後更新時間、可用性燈號、額度用量」。
 *
 * @param sourceCode     來源代碼（THREADS / GOOGLE_TRENDS / INSTAGRAM / MANUAL）
 * @param availability   可用性狀態（AVAILABLE / DEGRADED / UNAVAILABLE），UNAVAILABLE/DEGRADED 由前端對應紅／黃燈
 * @param enabled        是否啟用
 * @param lastFetchedAt  最後更新時間（+08:00）
 * @param quotaUsed      已用額度
 * @param quotaLimit     額度上限；null 表示無上限（如人工標記）
 */
public record HeatSourceDto(
        String sourceCode,
        String availability,
        Boolean enabled,
        OffsetDateTime lastFetchedAt,
        Integer quotaUsed,
        Integer quotaLimit) {
}
