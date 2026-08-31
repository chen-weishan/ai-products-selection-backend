package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.ProductInsightInput;
import com.example.ssds.ai.model.RecommendationInput;
import com.example.ssds.ai.model.FestivalMatch;
import com.example.ssds.ai.model.HeatBucket;
import com.example.ssds.ai.model.SceneClassifierInput;
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
