package com.example.ssds.api.sourcing.dto;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;

public record SourcingScoutResponse(
        Long itemId, Long productId, Long keywordId, Long drivingKeywordId, Long categoryId, String report,
        List<String> opportunitySignals, List<String> riskSignals,
        HeatStage heatStage, Short stageWeeks, Integer estimatedLifespanDays,
        Integer leadTimeDays, Integer timeGapDays, LocalDate statDate,
        SourcingStatus sourcingStatus, boolean canWatch,
        boolean canPrioritize, String prioritizeDisabledReason, String model,
        String modelAlias, String promptVersion, boolean cacheHit, OffsetDateTime generatedAt) {
    public static SourcingScoutResponse from(
            SourcingCandidate value, HeatCompositeDaily composite, ObjectMapper mapper, boolean cacheHit,
            SourcingPriorityCapabilities capabilities) {
        return from(value, null, composite, mapper, cacheHit, capabilities);
    }

    public static SourcingScoutResponse from(
            SourcingCandidate value, Long itemId, HeatCompositeDaily composite,
            ObjectMapper mapper, boolean cacheHit,
            SourcingPriorityCapabilities capabilities) {
        Integer lifespan = composite == null ? null : composite.getEstimatedLifespanDays();
        Integer liveGap = lifespan == null ? null : lifespan - value.getLeadTimeDays();
        return new SourcingScoutResponse(itemId, value.getProduct().getId(),
                value.getKeyword() == null ? null : value.getKeyword().getId(),
                value.getDrivingKeyword() == null ? null : value.getDrivingKeyword().getId(),
                value.getCategory() == null ? null : value.getCategory().getId(), value.getScoutReport(),
                read(mapper, value.getOpportunitySignals()), read(mapper, value.getRiskSignals()),
                composite == null ? null : composite.getStage(),
                composite == null ? null : composite.getStageWeeks(), lifespan,
                value.getLeadTimeDays(), liveGap,
                composite == null ? null : composite.getStatDate(),
                value.getProduct().getSourcingStatus(),
                capabilities.canWatch(), capabilities.canPrioritize(),
                capabilities.prioritizeDisabledReason(), value.getModel(), "MODEL_REASONING",
                value.getPromptVersion(), cacheHit, value.getReportGeneratedAt() == null ? null
                        : value.getReportGeneratedAt().atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime());
    }

    public static SourcingScoutResponse from(
            AiTaskItem item, ObjectMapper mapper, boolean cacheHit) {
        return new SourcingScoutResponse(
                item.getId(),
                item.getProduct() == null ? null : item.getProduct().getId(),
                null,
                null,
                item.getScoutCategory() == null ? null : item.getScoutCategory().getId(),
                item.getScoutReport(),
                read(mapper, item.getScoutOpportunitySignals()),
                read(mapper, item.getScoutRiskSignals()),
                null, null, null, null, null, null,
                item.getProduct() == null ? null : item.getProduct().getSourcingStatus(),
                true, false, "尚無每日熱度合成與時效落差資料",
                item.getScoutModel(), "MODEL_REASONING", item.getScoutPromptVersion(), cacheHit,
                item.getScoutReportGeneratedAt() == null ? null
                        : item.getScoutReportGeneratedAt().atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime());
    }

    private static List<String> read(ObjectMapper mapper, String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("尋源訊號 JSON 無法讀取", exception); }
    }
}
