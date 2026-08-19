package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import java.math.BigDecimal;
import java.time.Instant;

public record AiTaskResponse(
        Long taskId,
        AiTaskType taskType,
        TaskStatus status,
        int totalCount,
        int successCount,
        int failCount,
        int progressPercent,
        BigDecimal totalCostUsd,
        Instant startedAt,
        Instant finishedAt
) {
    public static AiTaskResponse from(AiTask task) {
        return new AiTaskResponse(
                task.getId(), task.getTaskType(), task.getStatus(), task.getTotalCount(),
                task.getSuccessCount(), task.getFailCount(), task.progressPercent(),
                task.getTotalCostUsd(), task.getStartedAt(), task.getFinishedAt());
    }
}
