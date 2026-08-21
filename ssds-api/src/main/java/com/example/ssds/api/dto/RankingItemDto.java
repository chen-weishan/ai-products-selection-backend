package com.example.ssds.api.dto;

import java.math.BigDecimal;

/**
 * FR-02 儀表板排行榜單筆項目。
 *
 * @param productId   品項 ID（點擊跳轉詳情用）
 * @param productName 品項名稱
 * @param finalScore  選品分數（0–100）
 * @param grade       分級（A / B / C）
 * @param sceneType   情境類型（VIRAL / FESTIVAL / REPLENISHMENT / SEASONAL）
 */
public record RankingItemDto(
                Long productId,
                String productName,
                BigDecimal finalScore,
                String grade,
                String sceneType) {
}