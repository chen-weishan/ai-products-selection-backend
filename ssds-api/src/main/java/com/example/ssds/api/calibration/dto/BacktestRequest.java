package com.example.ssds.api.calibration.dto;

import jakarta.validation.constraints.NotNull;

/** AC-15-4：任意兩個權重版本（含草稿）在同一批歷史資料上的比較。 */
public record BacktestRequest(
        @NotNull(message = "請選擇版本 A") Long versionAId,
        @NotNull(message = "請選擇版本 B") Long versionBId) {
}
