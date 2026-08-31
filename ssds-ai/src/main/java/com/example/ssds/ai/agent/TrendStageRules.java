package com.example.ssds.ai.agent;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.HeatStage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** v3.0 §FR-06 與 §5.8 的確定性降級規則。 */
final class TrendStageRules {
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
        List<TrendInterpreterInput.CompositePoint> sorted = series.stream()
                .sorted(Comparator.comparing(point -> LocalDate.parse(point.date())))
                .toList();
        TrendInterpreterInput.CompositePoint latest = sorted.getLast();
        if (latest.slope30d() != null
                && latest.slope30d().compareTo(DECLINE_THRESHOLD) < 0) {
            return HeatStage.DECLINING;
        }
        if (latest.slope30d() != null
                && latest.slope30d().signum() > 0
                && hasThreeConsecutiveGrowingWeeks(sorted)) {
            return HeatStage.RISING;
        }
        return HeatStage.PLATEAU;
    }

    private static boolean hasThreeConsecutiveGrowingWeeks(
            List<TrendInterpreterInput.CompositePoint> series) {
        Map<LocalDate, BigDecimal> values = new HashMap<>();
        for (var point : series) values.put(LocalDate.parse(point.date()), point.compositeValue());
        LocalDate latestDate = LocalDate.parse(series.getLast().date());
        BigDecimal week0 = values.get(latestDate);
        BigDecimal week1 = values.get(latestDate.minusDays(7));
        BigDecimal week2 = values.get(latestDate.minusDays(14));
        BigDecimal week3 = values.get(latestDate.minusDays(21));
        return week0 != null && week1 != null && week2 != null && week3 != null
                && week0.compareTo(week1) > 0
                && week1.compareTo(week2) > 0
                && week2.compareTo(week3) > 0;
    }
}
