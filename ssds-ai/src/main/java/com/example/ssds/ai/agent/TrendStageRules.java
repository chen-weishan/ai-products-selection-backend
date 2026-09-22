package com.example.ssds.ai.agent;

import com.example.ssds.ai.model.trend.*;
import com.example.ssds.core.domain.HeatStage;
import java.math.BigDecimal;
import java.util.List;

/** v3.0 §FR-06 與 §5.8 的確定性降級規則。 */
final class TrendStageRules {
    private static final BigDecimal RISE_THRESHOLD = new BigDecimal("0.10");
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("-0.10");

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
        if (latest.slope30d() != null
                && latest.slope30d().compareTo(DECLINE_THRESHOLD) < 0) {
            return HeatStage.DECLINING;
        }
        if (latest.slope30d() != null
                && latest.slope30d().compareTo(RISE_THRESHOLD) > 0) {
            return HeatStage.RISING;
        }
        return HeatStage.PLATEAU;
    }
}
