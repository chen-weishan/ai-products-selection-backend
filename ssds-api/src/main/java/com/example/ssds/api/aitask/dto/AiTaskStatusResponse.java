package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import java.time.Instant;

/** FR-03 評分輪詢所需的最小任務狀態；後續可由 FR-07 任務詳情沿用。 */
public record AiTaskStatusResponse(
        Long taskId,
        AiTaskType taskType,
        TaskStatus status,
        int totalCount,
        int successCount,
        int failCount,
        int progressPercent,
        Instant startedAt,
        Instant finishedAt
) {}
