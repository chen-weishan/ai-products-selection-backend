package com.example.ssds.api.calibration.dto;

/**
 * {@code POST /calibration/reports} 的回應：重算後的報告，以及自動建立的 Agent 7 解讀任務。
 *
 * @param interpretationTaskId 解讀任務 id；建立失敗時為 null，畫面提示可稍後再觸發
 */
public record GenerateCalibrationReportResponse(
        CalibrationReportResponse report,
        Long interpretationTaskId) {
}
