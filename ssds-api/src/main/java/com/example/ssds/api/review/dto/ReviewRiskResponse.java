package com.example.ssds.api.review.dto;

import com.example.ssds.ai.model.ReviewRiskAnalysis;
import com.example.ssds.ai.model.ReviewRiskResult;
import com.example.ssds.ai.model.ReviewTopicStatistic;
import java.time.Instant;
import java.util.List;

public record ReviewRiskResponse(
        Long productId,
        int reviewCount,
        int analyzedCount,
        List<ReviewRiskAnalysis> reviews,
        List<ReviewTopicStatistic> topicStatistics,
        boolean analysisCompleted,
        String statusMessage,
        Integer riskPenaltyOverride,
        boolean fallbackApplied,
        String fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion,
        Instant analyzedAt) {

    public static ReviewRiskResponse from(
            Long productId, int reviewCount, ReviewRiskResult result, Instant analyzedAt) {
        boolean completed = !result.fallbackApplied();
        return new ReviewRiskResponse(
                productId,
                reviewCount,
                result.output().reviews().size(),
                result.output().reviews(),
                result.output().topicStatistics(),
                completed,
                completed ? (reviewCount == 0 ? "評論資料不足" : null) : "評論分析未完成",
                completed ? null : 0,
                result.fallbackApplied(),
                result.fallbackReason() == null ? null : result.fallbackReason().name(),
                result.cacheHit(),
                result.model(),
                result.promptVersion(),
                analyzedAt);
    }
}
