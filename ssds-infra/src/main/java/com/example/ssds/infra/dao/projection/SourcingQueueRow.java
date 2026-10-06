package com.example.ssds.infra.dao.projection;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;

public record SourcingQueueRow(
        Long productId,
        String keyword,
        HeatStage heatStage,
        Short stageWeeks,
        Integer estimatedLifespanDays,
        Integer leadTimeDays,
        Integer timeGapDays,
        SourcingStatus sourcingStatus) {
}
