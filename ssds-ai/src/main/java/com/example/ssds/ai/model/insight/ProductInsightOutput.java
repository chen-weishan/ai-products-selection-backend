package com.example.ssds.ai.model.insight;

import java.util.List;

public record ProductInsightOutput(
        List<SellingPoint> sellingPoints,
        List<ProductInsightRisk> risks) {
    public ProductInsightOutput {
        sellingPoints = sellingPoints == null ? List.of() : List.copyOf(sellingPoints);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }

    public long supportedSellingPointCount() {
        return sellingPoints.stream().filter(item -> item.supportCount() > 0).count();
    }

    public long supportedRiskCount() {
        return risks.stream().filter(item -> item.supportCount() > 0).count();
    }

    public boolean hasSufficientEvidence() {
        return supportedSellingPointCount() >= 2 && supportedRiskCount() >= 2;
    }
}
