package com.example.ssds.api.insight.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.ProductInsightOutput;
import com.example.ssds.ai.model.ProductInsightResult;
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
                "product-insight-v1",
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
                "product-insight-v1",
                null,
                null,
                2);

        ProductInsightResponse response = ProductInsightResponse.from(102L, 30, result, OffsetDateTime.MIN);

        assertEquals("賣點與風險分析未完成", response.statusMessage());
    }
}
