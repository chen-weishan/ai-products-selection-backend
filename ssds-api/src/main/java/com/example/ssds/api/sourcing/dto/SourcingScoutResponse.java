package com.example.ssds.api.sourcing.dto;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;

public record SourcingScoutResponse(
        Long productId, Long keywordId, Long drivingKeywordId, Long categoryId, String report,
        List<String> opportunitySignals, List<String> riskSignals,
        HeatStage heatStage, Short stageWeeks, Integer estimatedLifespanDays,
        Integer timeGapDays, String model,
        String modelAlias, String promptVersion, OffsetDateTime generatedAt) {
    public static SourcingScoutResponse from(
            SourcingCandidate value, HeatCompositeDaily composite, ObjectMapper mapper) {
        return new SourcingScoutResponse(value.getProduct().getId(),
                value.getKeyword() == null ? null : value.getKeyword().getId(),
                value.getDrivingKeyword() == null ? null : value.getDrivingKeyword().getId(),
                value.getCategory() == null ? null : value.getCategory().getId(), value.getScoutReport(),
                read(mapper, value.getOpportunitySignals()), read(mapper, value.getRiskSignals()),
                composite == null ? null : composite.getStage(),
                composite == null ? null : composite.getStageWeeks(),
                composite == null ? null : composite.getEstimatedLifespanDays(),
                value.getTimeGapDays(), value.getModel(), "MODEL_REASONING",
                value.getPromptVersion(), value.getReportGeneratedAt() == null ? null
                        : value.getReportGeneratedAt().atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime());
    }
    private static List<String> read(ObjectMapper mapper, String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("尋源訊號 JSON 無法讀取", exception); }
    }
}
