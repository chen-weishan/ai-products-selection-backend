package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record AiTaskResponse(
        Long taskId,
        @Schema(description = "穩定的 API／資料庫任務代碼；SELLING_POINT 為 ProductInsightAgent 的相容碼")
        AiTaskType taskType,
        @Schema(description = "供 UI 顯示的功能名稱，不可作為 API request 代碼")
        String taskTypeDisplayName,
        AiTaskType.BudgetPool budgetPool,
        TaskStatus status,
        int totalCount,
        int successCount,
        int failCount,
        int cacheHitCount,
        int requestCount,
        int retryPoolRequestCount,
        int progressPercent,
        BigDecimal totalCostUsd,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    public static AiTaskResponse from(AiTask task) {
        return new AiTaskResponse(
                task.getId(), task.getTaskType(), task.getTaskType().displayName(),
                task.getBudgetPool(), task.getStatus(), task.getTotalCount(),
                task.getSuccessCount(), task.getFailCount(), task.getCacheHitCount(), task.getRequestCount(),
                task.getRetryPoolRequestCount(), task.progressPercent(),
                task.getTotalCostUsd(), toApiTime(task.getStartedAt()), toApiTime(task.getFinishedAt()));
    }

    private static OffsetDateTime toApiTime(Instant value) {
        return value == null ? null : value.atZone(API_ZONE).toOffsetDateTime();
    }
}
