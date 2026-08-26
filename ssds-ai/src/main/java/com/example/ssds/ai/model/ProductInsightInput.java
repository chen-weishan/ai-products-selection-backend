package com.example.ssds.ai.model;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Season;
import java.math.BigDecimal;
import java.util.List;

public record ProductInsightInput(
        Long productId,
        ProductBasic product,
        List<ReviewText> reviews,
        List<PenaltyDetail> penalties) {

    public ProductInsightInput {
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
        penalties = penalties == null ? List.of() : List.copyOf(penalties);
    }

    public record ProductBasic(
            String name,
            String category,
            Season season,
            String logisticsCondition) {}

    public record ReviewText(Long reviewId, String content) {}

    public record PenaltyDetail(
            FactorCode factorCode,
            BigDecimal penaltyValue,
            List<String> matchedTopics) {
        public PenaltyDetail {
            matchedTopics = matchedTopics == null ? List.of() : List.copyOf(matchedTopics);
        }
    }
}
