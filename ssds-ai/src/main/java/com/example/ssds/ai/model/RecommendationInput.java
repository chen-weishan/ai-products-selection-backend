package com.example.ssds.ai.model;

import com.example.ssds.core.domain.*;
import java.math.BigDecimal;
import java.util.List;

public record RecommendationInput(
        Long productId,
        List<FactorPercentile> factors,
        BigDecimal bonusSubtotal,
        BigDecimal penaltySubtotal,
        Grade grade,
        SceneType sceneType,
        List<FactorCode> matchedPenaltyRules,
        FestivalWindow festival,
        List<Integer> allowedQuantities) {

    public RecommendationInput {
        factors = factors == null ? List.of() : List.copyOf(factors);
        matchedPenaltyRules = matchedPenaltyRules == null ? List.of() : List.copyOf(matchedPenaltyRules);
        allowedQuantities = allowedQuantities == null ? List.of(0) : List.copyOf(allowedQuantities);
    }

    public record FactorPercentile(
            FactorCode factorCode,
            BigDecimal percentile,
            boolean dataAvailable) {}

    public record FestivalWindow(
            String festivalCode,
            String festivalName,
            int daysRemaining) {}
}
