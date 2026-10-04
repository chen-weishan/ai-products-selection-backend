package com.example.ssds.api.decision.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 預測準確度（規格書 §FR-11-3、§8.2 GET /decisions/accuracy）。
 *
 * <p>比率一律 0–1；分母為 0 時該指標為 null（不是 0——「沒有資料」與「全部失準」
 * 是兩件事，畫面要能分辨）。每個比率都附分子分母，讓畫面可以寫「5／7」而不只是「71%」，
 * 避免用小樣本講出過度自信的結論。
 */
public record DecisionAccuracyResponse(
        LocalDate from,
        LocalDate to,
        Long categoryId,
        Long decidedBy,
        /** 篩選範圍內的決策總數（含 WATCH／REJECT）。 */
        long totalDecisions,
        /** 已回填樣本數（§FR-11-3：統計效度的前提指標，須顯著呈現）。 */
        long sampleSize,
        /** 選品分數與實際銷量的皮爾森相關；樣本少於 2 筆或任一方無變異時為 null。 */
        BigDecimal scoreSalesCorrelation,
        /** A 級品項中售罄狀況為 EARLY_SELLOUT 或 ON_TIME 的比例。 */
        BigDecimal gradeAHitRate,
        long gradeAHitCount,
        long gradeASampleSize,
        /** 決策快照中情境經人工覆寫的比例（風險 R-13）。 */
        BigDecimal sceneOverrideRate,
        long sceneOverrideCount,
        /** followed_ai = true 的比例，分母為 totalDecisions（§FR-11-3）。 */
        BigDecimal aiAdoptionRate,
        long aiFollowedCount,
        int minSample,
        /** sampleSize < minSample 時為 true，畫面須顯示不可關閉的警示（AC-11-5）。 */
        boolean belowMinSample,
        String validityWarning) {
}
