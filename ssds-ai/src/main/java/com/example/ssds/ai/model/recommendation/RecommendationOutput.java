package com.example.ssds.ai.model.recommendation;

import com.example.ssds.core.domain.DecisionType;

public record RecommendationOutput(
        DecisionType action,
        int qtyMin,
        int qtyMax,
        String quantityText,
        String reasoning) {}
