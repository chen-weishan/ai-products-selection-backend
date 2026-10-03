package com.example.ssds.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AiTaskTypeTest {

    @Test
    void sellingPointKeepsStableCodeButDisplaysCombinedProductInsightName() {
        assertEquals("SELLING_POINT", AiTaskType.SELLING_POINT.name());
        assertEquals(
                "Product Insight（賣點與風險）",
                AiTaskType.SELLING_POINT.displayName());
    }
}
