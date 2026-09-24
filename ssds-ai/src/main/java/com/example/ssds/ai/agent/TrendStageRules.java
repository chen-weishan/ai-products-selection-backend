package com.example.ssds.ai.agent;

import com.example.ssds.ai.model.trend.*;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatTrendCalculator;
import java.util.List;

/** v3.0 §FR-06 與 §5.8 的確定性降級規則。 */
final class TrendStageRules {
    private TrendStageRules() {}

    static TrendInterpreterOutput evaluate(TrendInterpreterInput input) {
        HeatStage stage = determineStage(input.compositeSeries());
        return input.allowedOutputs().stream()
                .filter(candidate -> candidate.stage() == stage)
                .findFirst()
                .map(candidate -> new TrendInterpreterOutput(
                        candidate.stage(), candidate.stageWeeks(), candidate.estimatedLifespanDays()))
                .orElseThrow(() -> new IllegalArgumentException("allowedOutputs 缺少規則式階段"));
    }

    static HeatStage determineStage(List<TrendInterpreterInput.CompositePoint> series) {
        if (series.isEmpty()) return HeatStage.PLATEAU;
        TrendInterpreterInput.CompositePoint latest = series.stream()
                .max(java.util.Comparator.comparing(TrendInterpreterInput.CompositePoint::date))
                .orElseThrow();
        return HeatTrendCalculator.determineStage(latest.slope30d());
    }
}
