package com.example.ssds.ai.model;

import java.util.List;

public record ReviewRiskOutput(
        List<ReviewRiskAnalysis> reviews,
        List<ReviewTopicStatistic> topicStatistics
) {
    public ReviewRiskOutput {
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
        topicStatistics = topicStatistics == null ? List.of() : List.copyOf(topicStatistics);
    }
}
