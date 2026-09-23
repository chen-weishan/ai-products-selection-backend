package com.example.ssds.api.festival.dto;

import java.math.BigDecimal;

/**
 * S-20 標記 3、5 兩張設定表的現值。
 *
 * <p>規格書 §9 只定義了兩支 PUT，沒有對應的 GET；但 S-20 的畫面明確要顯示目前的
 * 前置天數與適溫區間，維護的人看不到現值就無從改起。故新增此讀取端點——
 * 這條規格書沒有，是設計決定。
 *
 * <p>尚未設定的欄位回 {@code null} 而非 0：AC-17-5 的判準是「有沒有資料」，
 * 用 0 代替會讓「適溫 0°C」與「沒填」變成同一件事。
 */
public record CategoryProfileResponse(
        Long categoryId,
        String categoryName,
        Integer leadTimeDays,
        BigDecimal idealTempMin,
        BigDecimal idealTempMax,
        BigDecimal tolerance) {
}
