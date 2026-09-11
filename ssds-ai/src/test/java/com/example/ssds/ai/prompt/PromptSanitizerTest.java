package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.ProductInsightInput;
import com.example.ssds.ai.model.RecommendationInput;
import com.example.ssds.ai.model.ReviewRiskInput;
import com.example.ssds.ai.model.SourcingScoutInput;
import com.example.ssds.ai.model.FestivalMatch;
import com.example.ssds.ai.model.HeatBucket;
import com.example.ssds.ai.model.SceneClassifierInput;
import com.example.ssds.ai.schema.TrendInterpreterResponseParserTest;
import com.example.ssds.ai.agent.WeightCalibrationAgentTest;
import com.example.ssds.ai.schema.RecommendationResponseParserTest;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.HeatStage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptSanitizerTest {
    private final PromptSanitizer sanitizer = new PromptSanitizer();

    @Test
    void sceneClassifierUsesWhitelistAndSanitizesTextFields() {
        SceneClassifierInput sanitized = sanitizer.sanitizeSceneClassifier(new SceneClassifierInput(
                101L,
                "  商品\u0001  ",
                9L,
                "  零食\u0002  ",
                Season.ALL,
                new BigDecimal("0.20"),
                new BigDecimal("0.10"),
                new BigDecimal("88"),
                HeatStage.RISING,
                HeatBucket.HIGH,
                3,
                List.of(new FestivalMatch("  MID_AUTUMN\u0003  ", new BigDecimal("0.90")))));

        assertAll(
                () -> assertEquals("商品", sanitized.productName()),
                () -> assertEquals("零食", sanitized.categoryName()),
                () -> assertEquals("MID_AUTUMN", sanitized.festivalMatches().getFirst().festivalCode()),
                () -> assertEquals(new BigDecimal("88"), sanitized.heatSlopePercentile()),
                () -> assertEquals(HeatStage.RISING, sanitized.heatStage()));
    }

    @Test
    void removesPersonalAndOrderIdentifiersBeforePromptAssembly() {
        String sanitized = sanitizer.sanitizeReviewText(
                "王小姐 user@example.com 0912-345-678 訂單 AB12345678，送到台北市信義區松仁路100號，@buyer88");

        assertFalse(sanitized.contains("user@example.com"));
        assertFalse(sanitized.contains("0912-345-678"));
        assertFalse(sanitized.contains("AB12345678"));
        assertFalse(sanitized.contains("松仁路100號"));
        assertFalse(sanitized.contains("buyer88"));
        assertTrue(sanitized.contains("[EMAIL]"));
        assertTrue(sanitized.contains("[PHONE]"));
        assertTrue(sanitized.contains("[ORDER]"));
        assertTrue(sanitized.contains("[ADDRESS]"));
    }

    @Test
    void masksTaiwanParenthesizedPhonesExtensionsAndSpacedAddressesWithoutMaskingOrdinaryNumbers() {
        String sanitized = sanitizer.sanitizeReviewText(
                "市話 (02) 2345-6789 分機 321，手機 +886 912 345 678，地址台北市中山區南京東路三段 100 號之 2；評分 88，日期 2026-09-10。");

        assertAll(
                () -> assertFalse(sanitized.contains("2345-6789")),
                () -> assertFalse(sanitized.contains("912 345 678")),
                () -> assertFalse(sanitized.contains("100 號之 2")),
                () -> assertEquals(2, sanitized.split("\\[PHONE]", -1).length - 1),
                () -> assertTrue(sanitized.contains("[ADDRESS]")),
                () -> assertTrue(sanitized.contains("評分 88")),
                () -> assertTrue(sanitized.contains("2026-09-10")));
    }

    @Test
    void masksStandaloneLaneAlleyAndAdministrativeAreaHouseNumberAddresses() {
        String sanitized = sanitizer.sanitizeReviewText(
                "請送到幸福巷 12 號之 3，備用地址是信義區松仁里 100 號；商品型號 100 號不是地址。");

        assertAll(
                () -> assertFalse(sanitized.contains("幸福巷 12 號之 3")),
                () -> assertFalse(sanitized.contains("信義區松仁里 100 號")),
                () -> assertTrue(sanitized.contains("商品型號 100 號")),
                () -> assertEquals(2, sanitized.split("\\[ADDRESS]", -1).length - 1));
    }

    @Test
    void reviewRiskWhitelistKeepsOnlyIdsAndDeidentifiedReviewText() throws Exception {
        ReviewRiskInput sanitized = sanitizer.sanitizeReviewRisk(
                101L,
                List.of(new ReviewRiskInput.ReviewText(
                        9L, "buyer@example.com 0912-345-678 說成本 100 元")));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sanitized);

        assertAll(
                () -> assertEquals(101L, sanitized.productId()),
                () -> assertEquals(9L, sanitized.reviews().getFirst().reviewId()),
                () -> assertFalse(json.contains("buyer@example.com")),
                () -> assertFalse(json.contains("0912-345-678")),
                () -> assertFalse(json.contains("cost")),
                () -> assertFalse(json.contains("supplier")));
    }

    @Test
    void productInsightUsesWhitelistAndSanitizesEveryReview() {
        ProductInsightInput input = new ProductInsightInput(
                101L,
                new ProductInsightInput.ProductBasic("商品", "零食", Season.ALL, "常溫"),
                List.of(new ProductInsightInput.ReviewText(
                        1L, "請聯絡 0912-345-678 或 buyer@example.com")),
                List.of());

        ProductInsightInput sanitized = sanitizer.sanitizeProductInsight(input);

        assertFalse(sanitized.reviews().getFirst().content().contains("0912-345-678"));
        assertFalse(sanitized.reviews().getFirst().content().contains("buyer@example.com"));
        assertEquals("商品", sanitized.product().name());
    }

    @Test
    void recommendationRetainsOnlyItsExplicitWhitelistedShape() {
        RecommendationInput sanitized = sanitizer.sanitizeRecommendation(
                RecommendationResponseParserTest.input());

        assertEquals(6, sanitized.factors().size());
        assertEquals(List.of(0, 200, 300), sanitized.allowedQuantities());
        assertEquals("MID_AUTUMN", sanitized.festival().festivalCode());
    }

    @Test
    void trendInterpreterWhitelistRetainsOnlySeriesSourcesAndAllowedOutputs() throws Exception {
        var sanitized = sanitizer.sanitizeTrendInterpreter(TrendInterpreterResponseParserTest.input());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sanitized);

        assertAll(
                () -> assertEquals(31L, sanitized.keywordId()),
                () -> assertFalse(sanitized.compositeSeries().isEmpty()),
                () -> assertFalse(sanitized.sourceTrends().isEmpty()),
                () -> assertFalse(sanitized.allowedOutputs().isEmpty()),
                () -> assertFalse(json.contains("productId")),
                () -> assertFalse(json.contains("sales")),
                () -> assertFalse(json.contains("supplier")));
    }

    @Test
    void sourcingWhitelistSanitizesLabelsWithoutAddingOperationalFields() throws Exception {
        SourcingScoutInput sanitized = sanitizer.sanitizeSourcingScout(
                new SourcingScoutInput("  低糖零食\u0001  ", 12L, "  進口零食\u0002  "));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sanitized);

        assertAll(
                () -> assertEquals("低糖零食", sanitized.keyword()),
                () -> assertEquals("進口零食", sanitized.categoryName()),
                () -> assertEquals(12L, sanitized.categoryId()),
                () -> assertFalse(json.contains("productId")),
                () -> assertFalse(json.contains("cost")),
                () -> assertFalse(json.contains("supplier")));
    }

    @Test
    void weightCalibrationShapeContainsOnlyAggregatedStatistics() throws Exception {
        var sanitized = sanitizer.sanitizeWeightCalibration(WeightCalibrationAgentTest.input());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sanitized);
        assertFalse(json.contains("salesRecord"));
        assertFalse(json.contains("campaignResult"));
        assertFalse(json.contains("productId"));
        assertEquals(1, sanitized.factors().size());
        assertEquals(200, sanitized.sceneOverrides().totalClassifications());
    }
}
