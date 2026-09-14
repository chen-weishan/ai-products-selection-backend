package com.example.ssds.api.review.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.review.ReviewRiskOutput;
import com.example.ssds.ai.model.review.ReviewRiskResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRiskResponseTest {
    @Test
    void exposesUsageFromImmediateAgentResult() {
        ReviewRiskResult result = new ReviewRiskResult(
                new ReviewRiskOutput(List.of(), List.of()),
                false,
                null,
                false,
                "model",
                "review-risk-v1",
                100,
                30,
                2);

        ReviewRiskResponse response = ReviewRiskResponse.from(102L, 30, result, Instant.EPOCH);

        assertEquals(100, response.promptTokens());
        assertEquals(30, response.completionTokens());
        assertEquals(2, response.requestCount());
    }

    @Test
    void noReviewsUsesDistinctDataAbsenceMessageAndZeroPenalty() {
        ReviewRiskResult result = new ReviewRiskResult(
                new ReviewRiskOutput(List.of(), List.of()),
                false,
                null,
                false,
                "not-invoked",
                "review-risk-v1",
                null,
                null,
                0);

        ReviewRiskResponse response = ReviewRiskResponse.from(102L, 0, result, Instant.EPOCH);

        assertEquals("評論風險分析未執行：無評論資料，評論風險扣分計為 0", response.statusMessage());
        assertEquals(0, response.riskPenaltyOverride());
    }

    @Test
    void modelFailureUsesSpecificationMessage() {
        ReviewRiskResult result = new ReviewRiskResult(
                new ReviewRiskOutput(List.of(), List.of()),
                true,
                FallbackReason.AI_UNAVAILABLE,
                false,
                "fallback-model",
                "review-risk-v1",
                null,
                null,
                3);

        ReviewRiskResponse response = ReviewRiskResponse.from(102L, 30, result, Instant.EPOCH);

        assertEquals("評論分析未完成", response.statusMessage());
        assertEquals(0, response.riskPenaltyOverride());
        assertEquals(3, response.requestCount());
    }
}
