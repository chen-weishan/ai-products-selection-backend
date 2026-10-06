package com.example.ssds.api.sourcing.dto;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;

public record SourcingQueueItemResponse(
        Long productId,
        String keyword,
        HeatStage heatStage,
        Short stageWeeks,
        Integer estimatedLifespanDays,
        Integer leadTimeDays,
        Integer timeGapDays,
        SourcingStatus sourcingStatus) {
}
