package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §6.8：鎖定七個 Agent 真正交給 Client 的 userPrompt JSON，而非只測內部 DTO。 */
class OutboundPromptContractTest {
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "productId", "reviewId", "keywordId", "categoryId",
            "supplier", "supplierId", "supplierName", "supplierContact", "supplierPhone", "quote",
            "cost", "price", "suggestedPrice", "margin", "marginRate", "actualSales", "realizedMargin",
            "memberProfile", "customerPriceBand");

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptSanitizer sanitizer = new PromptSanitizer();

    @Test
    void allSevenAgentPayloadsContainOnlyTheirApprovedExternalShape() throws Exception {
        JsonNode scene = json(new SceneClassifierPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeSceneClassifier(sceneInput())));
        assertKeys(scene, "productName", "categoryName", "heatSlopePercentile", "heatStage",
                "historicalCampaignCount", "festivalMatches");

        JsonNode review = json(new ReviewRiskPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeReviewRisk(101L, List.of(
                        new ReviewRiskInput.ReviewText(9001L,
                                "請聯絡 (02) 2345-6789，寄到台北市中山區南京東路三段 100 號"),
                        new ReviewRiskInput.ReviewText(9002L, "buyer@example.com 的包裝破損")))));
        assertKeys(review, "reviews");
        assertKeys(review.path("reviews").get(0), "reviewIndex", "content");
        assertEquals(0, review.path("reviews").get(0).path("reviewIndex").asInt());
        assertTrue(review.toString().contains("[PHONE]"));
        assertTrue(review.toString().contains("[ADDRESS]"));
        assertTrue(review.toString().contains("[EMAIL]"));

        JsonNode insight = json(new ProductInsightPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeProductInsight(insightInput())));
        assertKeys(insight, "product", "reviews", "penalties");
        assertKeys(insight.path("reviews").get(0), "content");

        JsonNode recommendation = json(new RecommendationPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeRecommendation(recommendationInput())));
        assertKeys(recommendation, "factors", "bonusSubtotal", "penaltySubtotal", "grade", "sceneType",
                "matchedPenaltyRules", "festival", "allowedQuantities");

        JsonNode trend = json(new TrendInterpreterPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeTrendInterpreter(trendInput())));
        assertKeys(trend, "compositeSeries", "sourceTrends", "allowedOutputs");
        assertKeys(trend.path("sourceTrends").get(0),
                "source", "granularity", "slope7d", "slope30d", "availability");

        JsonNode sourcing = json(new SourcingScoutPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeSourcingScout(new SourcingScoutInput("低糖零食", 12L, "進口零食"))));
        assertKeys(sourcing, "keyword", "categoryName");

        JsonNode calibration = json(new WeightCalibrationPromptFactory(mapper).userPrompt(
                sanitizer.sanitizeWeightCalibration(calibrationInput())));
        assertKeys(calibration, "quarter", "sampleSize", "regressionMethod", "factors", "regressionNote",
                "sceneOverrides", "backtests", "backtestNote");

        for (JsonNode payload : List.of(scene, review, insight, recommendation, trend, sourcing, calibration)) {
            assertNoForbiddenKeys(payload);
        }
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    private static void assertKeys(JsonNode node, String... expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(Set.of(expected), actual);
    }

    private static void assertNoForbiddenKeys(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                assertFalse(FORBIDDEN_KEYS.contains(field.getKey()), "forbidden outbound key: " + field.getKey());
                assertNoForbiddenKeys(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(OutboundPromptContractTest::assertNoForbiddenKeys);
        }
    }

    private static SceneClassifierInput sceneInput() {
        return new SceneClassifierInput(101L, "測試商品", 12L, "零食", Season.ALL,
                new BigDecimal("0.20"), new BigDecimal("0.10"), new BigDecimal("88"),
                HeatStage.RISING, HeatBucket.HIGH, 3,
                List.of(new FestivalMatch("MID_AUTUMN", new BigDecimal("0.9"))));
    }

    private static ProductInsightInput insightInput() {
        return new ProductInsightInput(101L,
                new ProductInsightInput.ProductBasic("測試商品", "零食", Season.ALL, "常溫"),
                List.of(new ProductInsightInput.ReviewText(9001L, "電話 0912-345-678，品質穩定")),
                List.of(new ProductInsightInput.PenaltyDetail(
                        FactorCode.REVIEW_RISK, new BigDecimal("5"), List.of("QUALITY"))));
    }

    private static RecommendationInput recommendationInput() {
        return new RecommendationInput(101L,
                List.of(new RecommendationInput.FactorPercentile(FactorCode.TREND, new BigDecimal("88"), true)),
                new BigDecimal("75"), new BigDecimal("5"), Grade.B, SceneType.REPLENISHMENT,
                List.of(FactorCode.REVIEW_RISK),
                new RecommendationInput.FestivalWindow("MID_AUTUMN", "中秋節", 20),
                List.of(0, 100, 200));
    }

    private static TrendInterpreterInput trendInput() {
        return new TrendInterpreterInput(31L,
                List.of(new TrendInterpreterInput.CompositePoint(
                        "2026-09-01", new BigDecimal("80"), new BigDecimal("0.12"), new BigDecimal("0.15"))),
                List.of(new TrendInterpreterInput.SourceTrend(
                        HeatSourceCode.GOOGLE_TRENDS, HeatGranularity.CATEGORY, 12L,
                        new BigDecimal("0.12"), new BigDecimal("0.15"), SourceAvailability.AVAILABLE)),
                List.of(new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 1, 56)));
    }

    private static WeightCalibrationInput calibrationInput() {
        return new WeightCalibrationInput("2026Q3", 200, "OLS",
                List.of(new WeightCalibrationInput.FactorStatistic(
                        "TREND", new BigDecimal("0.7"), new BigDecimal("0.2"),
                        new BigDecimal("0.25"), new BigDecimal("0.01"))),
                "彙總統計",
                new WeightCalibrationInput.OverrideStatistics(
                        100, 10, new BigDecimal("0.1"), List.of()),
                List.of(new WeightCalibrationInput.BacktestStatistic(
                        "candidate", new BigDecimal("0.72"), new BigDecimal("0.8"))),
                "回測彙總");
    }
}
