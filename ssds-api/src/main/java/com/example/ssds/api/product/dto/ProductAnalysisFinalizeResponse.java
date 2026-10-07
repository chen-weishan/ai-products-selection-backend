package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.TaskStatus;

/** 完成品項評分輸入儲存後，由後端統一決定是否需要建立完整分析任務。 */
public record ProductAnalysisFinalizeResponse(
        Long taskId,
        TaskStatus taskStatus,
        boolean queued
) {
}
