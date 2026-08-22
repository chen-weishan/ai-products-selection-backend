package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record AiTaskResponse(
        Long taskId,
        AiTaskType taskType,
        TaskStatus status,
        int totalCount,
        int successCount,
        int failCount,
        int progressPercent,
        BigDecimal totalCostUsd,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    public static AiTaskResponse from(AiTask task) {
        return new AiTaskResponse(
                task.getId(), task.getTaskType(), task.getStatus(), task.getTotalCount(),
                task.getSuccessCount(), task.getFailCount(), task.progressPercent(),
                task.getTotalCostUsd(), toApiTime(task.getStartedAt()), toApiTime(task.getFinishedAt()));
    }

    private static OffsetDateTime toApiTime(Instant value) {
        return value == null ? null : value.atZone(API_ZONE).toOffsetDateTime();
    }
}
