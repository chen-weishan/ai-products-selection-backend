package com.example.ssds.api.dto;

import java.math.BigDecimal;

/**
 * FR-02 儀表板排行榜單筆項目。
 *
 * @param productId       品項 ID（點擊跳轉詳情用，AC-02-2）
 * @param productName     品項名稱
 * @param finalScore      選品分數（0–100）
 * @param grade           分級（A / B / C，各榜獨立門檻 §5.6）
 * @param sceneType       情境判定標籤（VIRAL / FESTIVAL / REPLENISHMENT / SEASONAL）
 * @param sceneOverridden 人工覆寫標記：該品項的情境判定經人工覆寫且目前情境為覆寫結果
 * @param riskLevel       風險等級：HIGH（紅點）、MEDIUM（黃點）、NONE（無風險點），對應 UI 風險欄
 */
public record RankingItemDto(
                Long productId,
                String productName,
                BigDecimal finalScore,
                String grade,
                String sceneType,
                boolean sceneOverridden,
                String riskLevel) {
}
