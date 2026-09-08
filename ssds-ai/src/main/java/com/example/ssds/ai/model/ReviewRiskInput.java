package com.example.ssds.ai.model;

import java.util.List;

public record ReviewRiskInput(Long productId, List<ReviewText> reviews) {
    public ReviewRiskInput {
        reviews = reviews == null ? List.of() : List.copyOf(reviews);
    }

    public record ReviewText(Long reviewId, String content) {}
}
