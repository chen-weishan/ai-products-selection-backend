package com.example.ssds.api.risk;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.example.ssds.infra.entity.RiskAlert;

public record RiskAlertResponse(
        Long id,
        Long productId,
        String productName,
        Long categoryId,
        String categoryName,
        String riskType,
        String severity,
        String triggerValue,
        String impact,
        OffsetDateTime detectedAt,
        String status,
        String ignoreReason,
        OffsetDateTime handledAt,
        Long handledBy,
        String handledByName) {

    public static RiskAlertResponse from(RiskAlert alert, String impact) {
        return new RiskAlertResponse(
                alert.getId(),
                alert.getProduct().getId(),
                alert.getProduct().getName(),
                alert.getProduct().getCategory().getId(),
                alert.getProduct().getCategory().getName(),
                alert.getRiskType(),
                alert.getSeverity().name(),
                alert.getTriggerValue(),
                impact,
                alert.getDetectedAt().atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime(),
                alert.getStatus().name(),
                alert.getIgnoreReason(),
                alert.getHandledAt() == null ? null : alert.getHandledAt().atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime(),
                alert.getHandledBy() == null ? null : alert.getHandledBy().getId(),
                alert.getHandledBy() == null ? null : alert.getHandledBy().getDisplayName());
    }
}