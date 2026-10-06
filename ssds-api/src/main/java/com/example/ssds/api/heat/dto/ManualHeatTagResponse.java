package com.example.ssds.api.heat.dto;

import java.time.Instant;

/**
 * FR-14-1 人工熱度標記的回應內容。
 *
 * @param currentWeight 依 {@link #observedAt} 換算到「現在」的階梯衰減權重
 *                      （1.0／0.5／0.0），對應畫面「顯示現行權重」的要求（AC-14-2）
 */
public record ManualHeatTagResponse(
        Long id,
        String sourceUrl,
        String platform,
        short heatLevel,
        Long productId,
        String productName,
        Long keywordId,
        String keywordText,
        Instant observedAt,
        Long taggedById,
        String taggedByName,
        String note,
        double currentWeight) {
}
