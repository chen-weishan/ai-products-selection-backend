package com.example.ssds.api.score.dto;

import java.math.BigDecimal;
import java.util.List;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;

/**
 * 排行榜的一列（規格書 §FR-04「顯示內容」、S-05 畫面）。
 *
 * <p>
 * {@code penaltySubtotal} 為 <b>正值</b>（AC-04-7）：資料庫存 0–40 的正數，
 * 負號由 UI 加。後端回負值會讓前端加第二次負號而變成正的。
 *
 * <p>
 * {@code isPrimary = false} 代表這是次要情境的分數（§FR-04 多情境評分）。
 * 同一品項在同一 period 可以同時出現在多張榜，各榜分數不同——這不是重複資料。
 */
public record ScoreRankingRowResponse(
        Long scoreId,
        Long productId,
        String productName,
        String categoryName,
        SceneType sceneType,
        boolean isPrimary,
        BigDecimal bonusSubtotal,
        BigDecimal penaltySubtotal,
        BigDecimal finalScore,
        Grade grade,
        int confidence,
        boolean lowConfidence,
        boolean riskSuppressed,
        List<ScoreFactorBarResponse> factors) {
}