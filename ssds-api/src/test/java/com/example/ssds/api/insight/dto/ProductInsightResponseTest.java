package com.example.ssds.api.insight.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.insight.ProductInsightOutput;
import com.example.ssds.ai.model.insight.ProductInsightRisk;
import com.example.ssds.ai.model.insight.ProductInsightResult;
import com.example.ssds.ai.model.insight.SellingPoint;
import com.example.ssds.core.domain.InsightRiskType;
import com.example.ssds.core.domain.Severity;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductInsightResponseTest {
    @Test
    void noReviewsUsesAgentSpecificDataAbsenceMessage() {
        ProductInsightResult result = new ProductInsightResult(
                new ProductInsightOutput(List.of(), List.of()),
                false,
                null,
                false,
                "not-invoked",
                "product-insight-v2",
                null,
                null,
                0);

        ProductInsightResponse response = ProductInsightResponse.from(102L, 0, result, OffsetDateTime.MIN);

        assertEquals("賣點與風險分析未執行：無評論資料", response.statusMessage());
    }

    @Test
    void modelFailureUsesSpecificationMessage() {
        ProductInsightResult result = new ProductInsightResult(
                new ProductInsightOutput(List.of(), List.of()),
                true,
                FallbackReason.SCHEMA_INVALID,
                false,
                "fallback-model",
                "product-insight-v2",
                null,
                null,
                2);

        ProductInsightResponse response = ProductInsightResponse.from(102L, 30, result, OffsetDateTime.MIN);

        assertEquals("賣點與風險分析未完成", response.statusMessage());
    }

    @Test
    void distinguishesSellingPointRiskAndCombinedEvidenceShortage() {
        assertAll(
                () -> assertEvidenceMessage(output(1, 2), "賣點證據不足"),
                () -> assertEvidenceMessage(output(2, 1), "風險證據不足"),
                () -> assertEvidenceMessage(output(1, 1), "賣點證據不足／風險證據不足"));
    }

    private static void assertEvidenceMessage(ProductInsightOutput output, String expected) {
        ProductInsightResult result = new ProductInsightResult(
                output, false, null, false, "model", "prompt", 10, 5, 1);

        ProductInsightResponse response = ProductInsightResponse.from(
                102L, 3, result, OffsetDateTime.MIN);

        assertFalse(response.fallbackApplied());
        assertFalse(response.analysisCompleted());
        assertEquals(expected, response.statusMessage());
    }

    private static ProductInsightOutput output(int sellingCount, int riskCount) {
        List<SellingPoint> sellingPoints = java.util.stream.IntStream.range(0, sellingCount)
                .mapToObj(index -> new SellingPoint("賣點" + index, 1, "面向"))
                .toList();
        List<ProductInsightRisk> risks = java.util.stream.IntStream.range(0, riskCount)
                .mapToObj(index -> new ProductInsightRisk(
                        "風險" + index, 1, InsightRiskType.OTHER, Severity.LOW, false))
                .toList();
        return new ProductInsightOutput(sellingPoints, risks);
    }
}
