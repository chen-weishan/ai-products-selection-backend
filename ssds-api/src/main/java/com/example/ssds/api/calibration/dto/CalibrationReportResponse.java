package com.example.ssds.api.calibration.dto;

import com.example.ssds.core.domain.CalibrationStatus;
import java.time.Instant;
import java.util.List;

/**
 * 校準報告（S-19 主畫面，§FR-15「顯示內容」）。
 *
 * <p>{@code regression}／{@code backtest} 原樣回傳資料庫 JSON（轉成 Map／List），欄位定義見
 * {@code CalibrationReportService} 的 Javadoc。
 *
 * @param belowMinSample AC-15-1：true 時畫面置頂顯示黃色警示，不可關閉
 * @param baseVersionStale 待審核報告的基準版本已不是現行版本（報告產生後 FR-08 又核准了新版本）。
 *        此時核准會以已被取代的版本為底建草稿，畫面應提示先重新產生報告；已審核或舊格式報告恆為 false
 * @param aiInterpretation Agent 7 尚未執行或失敗時為 null，畫面改顯示統計原始結果（§6.3 Agent 7 降級）
 * @param acceptedFactors 部分採納時勾選的因子；核准時為全部有調整的因子
 * @param weightVersionId 核准／部分採納後建立的權重版本草稿（AC-15-6）
 */
public record CalibrationReportResponse(
        Long id,
        String quarter,
        int sampleSize,
        int minSample,
        boolean belowMinSample,
        String validityWarning,
        CalibrationStatus status,
        boolean baseVersionStale,
        Object regression,
        Object backtest,
        String aiInterpretation,
        Object adjustmentAdvice,
        Object attentionNotes,
        String aiModel,
        Instant interpretedAt,
        List<String> acceptedFactors,
        String reviewedBy,
        Instant reviewedAt,
        Long weightVersionId,
        String weightVersionNo,
        Instant createdAt) {
}
