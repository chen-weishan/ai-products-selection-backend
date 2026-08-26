package com.example.ssds.ai.model;

import java.util.List;

public record ProductInsightOutput(
        List<SellingPoint> sellingPoints,
        List<ProductInsightRisk> risks) {
    public ProductInsightOutput {
        sellingPoints = sellingPoints == null ? List.of() : List.copyOf(sellingPoints);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }
}
