package com.example.ssds.api.dto;

/**
 * FR-02 選品儀表板 KPI 四項數字。
 *
 * @param totalCandidates      候選品項總數
 * @param aGradeCount          A 級主推品項數（四榜合計後依品項去重）
 * @param openRiskCount        未處理的高風險示警數
 * @param overdueFeedbackCount 結案逾 7 天未回填數
 */
public record KpiDto(
        long totalCandidates,
        long aGradeCount,
        long openRiskCount,
        long overdueFeedbackCount) {
}