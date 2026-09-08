package com.example.ssds.api.review.dto;

import com.example.ssds.ai.model.ReviewRiskAnalysis;
import com.example.ssds.ai.model.ReviewRiskResult;
import com.example.ssds.ai.model.ReviewTopicStatistic;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
        OffsetDateTime analyzedAt) {

    private static final int MINIMUM_REVIEW_SAMPLE = 20;
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    public static ReviewRiskResponse from(
            Long productId, int reviewCount, ReviewRiskResult result, Instant analyzedAt) {
        boolean completed = !result.fallbackApplied();
        String statusMessage = result.fallbackApplied()
                ? "評論分析未完成"
                : reviewCount == 0
                        ? "評論風險分析未執行：無評論資料，評論風險扣分計為 0"
                        : reviewCount < MINIMUM_REVIEW_SAMPLE
                                ? "評論樣本不足（少於 20 則），評論風險扣分計為 0"
                                : null;
        return new ReviewRiskResponse(
                productId,
                reviewCount,
                result.output().reviews().size(),
                result.output().reviews(),
                result.output().topicStatistics(),
                completed,
                statusMessage,
                result.fallbackApplied() || reviewCount < MINIMUM_REVIEW_SAMPLE ? 0 : null,
                result.fallbackApplied(),
                result.fallbackReason() == null ? null : result.fallbackReason().name(),
                result.cacheHit(),
                result.model(),
                result.promptVersion(),
                toApiTime(analyzedAt));
    }

    private static OffsetDateTime toApiTime(Instant value) {
        return value == null ? null : value.atZone(API_ZONE).toOffsetDateTime();
    }
}
