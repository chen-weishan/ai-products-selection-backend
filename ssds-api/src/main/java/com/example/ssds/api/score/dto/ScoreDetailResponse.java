package com.example.ssds.api.score.dto;

import java.math.BigDecimal;
import java.util.List;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;

/**
 * 單一品項在某一 period、某一情境下的分數快照
 * （規格書 §8.2 GET /products/{id}/scores，含九項因子明細）。
 *
 * <p>加分與扣分<b>分成兩個欄位</b>，不是混在同一個陣列讓前端自己用 isPenalty 拆——
 * §FR-04 要求「扣分明細以獨立卡片呈現，視覺上與加分區分離」。
 *
 * <p>兩個清單刻意重用既有的 record：{@link ScoreFactorBarResponse} 與
 * {@link ScoreDeductionsResponse.DeductionItem}。同一個概念在不同端點回同一種形狀，
 * 前端的型別可以共用，也不會出現「排行榜的因子」和「詳情的因子」欄位對不起來的問題。
 *
 * <p>{@code penaltySubtotal} 為<b>正值</b>（AC-04-7）；
 * {@code isPrimary = false} 代表這是次要情境的分數（§FR-04 多情境評分）。
 */
public record ScoreDetailResponse(
        Long scoreId,
        Long productId,
        String productName,
        String categoryName,

        /** ISO 週，如 2026W36。 */
        String period,

        SceneType sceneType,

        /** 主情境為 true；次要情境為 false。 */
        boolean isPrimary,

        BigDecimal bonusSubtotal,

        /** 扣分小計，0–40 的正值。 */
        BigDecimal penaltySubtotal,

        BigDecimal finalScore,
        Grade grade,
        int confidence,

        /** §5.9：信心度低於 50 時 UI 顯示警示標記。門檻由後端判斷。 */
        boolean lowConfidence,

        /** §5.6：扣分達 20（含）以上，分級最高只給 B。門檻由後端判斷。 */
        boolean riskSuppressed,

        /**
         * 六個加分因子，依 {@code FactorCode} 宣告順序：
         * TREND → MARGIN → CVR → PRICE_FIT → FESTIVAL → CLIMATE
         * （§FR-04「因子組成」指定的顯示順序）。
         */
        List<ScoreFactorBarResponse> bonusFactors,

        /**
         * 三個扣分因子，依宣告順序：
         * REVIEW_RISK → LOGISTICS_RISK → INVENTORY_RISK。
         */
        List<ScoreDeductionsResponse.DeductionItem> penaltyFactors) {
}
