package com.example.ssds.ai.model;

import com.example.ssds.core.domain.*;
import java.math.BigDecimal;
import java.util.List;

public record TrendInterpreterInput(
        Long keywordId,
        List<CompositePoint> compositeSeries,
        List<SourceTrend> sourceTrends,
        List<AllowedOutput> allowedOutputs) {

    public TrendInterpreterInput {
        compositeSeries = compositeSeries == null ? List.of() : List.copyOf(compositeSeries);
        sourceTrends = sourceTrends == null ? List.of() : List.copyOf(sourceTrends);
        allowedOutputs = allowedOutputs == null ? List.of() : List.copyOf(allowedOutputs);
    }

    public record CompositePoint(
            String date,
            BigDecimal compositeValue,
            BigDecimal slope7d,
            BigDecimal slope30d) {}

    public record SourceTrend(
            HeatSourceCode source,
            BigDecimal slope7d,
            BigDecimal slope30d,
            SourceAvailability availability) {}

    public record AllowedOutput(
            HeatStage stage,
            int stageWeeks,
            int estimatedLifespanDays) {}
}
