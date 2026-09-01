package com.example.ssds.api.review;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.ReviewRiskAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class ReviewRiskServiceTest {
    @Test
    void passesFiftyReviewBucketAndLatestReviewDateToAgent() {
        ProductRepository products = mock(ProductRepository.class);
        ProductReviewRepository reviews = mock(ProductReviewRepository.class);
        ReviewAnalysisRepository analyses = mock(ReviewAnalysisRepository.class);
        PromptSanitizer sanitizer = mock(PromptSanitizer.class);
        ReviewRiskAgent agent = mock(ReviewRiskAgent.class);
        Product product = Product.builder().id(101L).trackType(TrackType.A).build();
        ProductReview older = review(1L, product, LocalDate.of(2026, 8, 10));
        ProductReview latest = review(2L, product, LocalDate.of(2026, 8, 20));
        ReviewRiskInput sanitized = new ReviewRiskInput(101L, List.of());
        ReviewRiskResult fallback = new ReviewRiskResult(
                new ReviewRiskOutput(List.of(), List.of()),
                true,
                FallbackReason.AI_UNAVAILABLE,
                false,
                "rule-fallback",
                "review-risk-v1");
        when(products.findById(101L)).thenReturn(Optional.of(product));
        when(reviews.countByProductId(101L)).thenReturn(76L);
        when(reviews.findByProductId(eq(101L), any())).thenReturn(new PageImpl<>(List.of(latest, older)));
        when(sanitizer.sanitizeReviewRisk(eq(101L), anyList())).thenReturn(sanitized);
        when(agent.analyze(sanitized, 1, LocalDate.of(2026, 8, 20), false)).thenReturn(fallback);

        new ReviewRiskService(products, reviews, analyses, sanitizer, agent).analyze(101L, false);

        verify(agent).analyze(sanitized, 1, LocalDate.of(2026, 8, 20), false);
    }

    private static ProductReview review(Long id, Product product, LocalDate reviewedAt) {
        return ProductReview.builder()
                .id(id)
                .product(product)
                .source("test")
                .content("評論 " + id)
                .contentHash("hash-" + id)
                .reviewedAt(reviewedAt)
                .build();
    }
}
