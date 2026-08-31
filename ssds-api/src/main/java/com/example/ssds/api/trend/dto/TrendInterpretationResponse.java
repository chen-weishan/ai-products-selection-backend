package com.example.ssds.api.trend.dto;

import com.example.ssds.ai.model.TrendInterpreterResult;
import com.example.ssds.core.domain.HeatStage;
import java.time.OffsetDateTime;

public record TrendInterpretationResponse(
        Long keywordId,
        HeatStage stage,
        int stageWeeks,
        int estimatedLifespanDays,
        boolean fallbackApplied,
        String fallbackReason,
        boolean cacheHit,
        String model,
        String modelAlias,
        String promptVersion,
        int requestCount,
        OffsetDateTime generatedAt) {

    public static TrendInterpretationResponse from(
            Long keywordId, TrendInterpreterResult result, OffsetDateTime generatedAt) {
        return new TrendInterpretationResponse(
                keywordId,
                result.output().stage(),
                result.output().stageWeeks(),
                result.output().estimatedLifespanDays(),
                result.fallbackApplied(),
                result.fallbackReason() == null ? null : result.fallbackReason().name(),
                result.cacheHit(),
                result.model(),
                "MODEL_NUMERIC",
                result.promptVersion(),
                result.requestCount(),
                generatedAt);
    }
}
