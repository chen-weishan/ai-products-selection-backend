package com.example.ssds.api.score.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;

/**
 * 歷史分數趨勢的一個點（規格書 §8.2 GET /products/{id}/scores/history）。
 *
 * <p><b>沒有因子明細。</b>趨勢圖只畫分數線，每個點都帶九根長條會讓回應膨脹好幾倍，
 * 而畫面根本不用。要看某一點的因子組成，改打
 * {@code GET /products/{id}/scores?period=&scene=}（2B）。
 *
 * <p><b>{@code isActive} 是本端點特有的欄位。</b>§5.10 重算不覆寫、歷史列全部保留，
 * 所以這支<b>不過濾</b> {@code is_active}——同一組 (period, sceneType) 可能有多個點。
 * 前端要靠這個旗標分辨哪一點是現行分數、哪些是被取代的舊值
 * （例如舊值畫淡色或虛線）。少了它，圖上會出現同一週兩個點而看不出差別。
 *
 * <p>{@code penaltySubtotal} 為正值（AC-04-7）；{@code calculatedAt} 依 §8.1 以 +08:00 呈現。
 */
public record ScoreHistoryPointResponse(
        Long scoreId,

        /** ISO 週，如 2026W36。趨勢圖的 X 軸通常用這個而不是 calculatedAt。 */
        String period,

        SceneType sceneType,

        /** 主情境為 true；同一 period 的次要情境為 false（§FR-04 多情境評分）。 */
        boolean isPrimary,

        /** true 為現行分數；false 為被後續重算取代的歷史值（§5.10）。 */
        boolean isActive,

        BigDecimal bonusSubtotal,

        /** 扣分小計，0–40 的正值。 */
        BigDecimal penaltySubtotal,

        BigDecimal finalScore,
        Grade grade,
        int confidence,

        /** 實際評分時刻。同一 period 內重算多次時，用它區分先後。 */
        OffsetDateTime calculatedAt) {
}
