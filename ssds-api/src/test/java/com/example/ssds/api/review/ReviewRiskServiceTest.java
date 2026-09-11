package com.example.ssds.api.review;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.ReviewRiskAgent;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.review.*;
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

    @Test
    void noReviewsHasExplicitNotExecutedMessageAndZeroPenalty() {
        Fixture fixture = fixture(102L, List.of(), 0L);
        ReviewRiskInput input = new ReviewRiskInput(102L, List.of());
        when(fixture.sanitizer.sanitizeReviewRisk(102L, List.of())).thenReturn(input);
        when(fixture.agent.analyze(input, 0, null, false)).thenReturn(success(List.of()));

        var response = fixture.service.analyze(102L, false);

        assertAll(
                () -> assertTrue(response.analysisCompleted()),
                () -> assertEquals(0, response.riskPenaltyOverride()),
                () -> assertEquals(
                        "評論風險分析未執行：無評論資料，評論風險扣分計為 0",
                        response.statusMessage()),
                () -> assertFalse(response.fallbackApplied()));
        verify(fixture.analyses).saveAll(List.of());
    }

    @Test
    void fewerThanTwentyReviewsHasSampleInsufficientMessageAndZeroPenalty() {
        Product product = Product.builder().id(103L).trackType(TrackType.A).build();
        List<ProductReview> source = List.of(
                review(1L, product, LocalDate.of(2026, 8, 10)),
                review(2L, product, LocalDate.of(2026, 8, 20)));
        Fixture fixture = fixture(product, source, 2L);
        ReviewRiskInput input = new ReviewRiskInput(103L, List.of());
        when(fixture.sanitizer.sanitizeReviewRisk(eq(103L), anyList())).thenReturn(input);
        when(fixture.agent.analyze(input, 0, LocalDate.of(2026, 8, 20), false))
                .thenReturn(success(List.of()));

        var response = fixture.service.analyze(103L, false);

        assertAll(
                () -> assertTrue(response.analysisCompleted()),
                () -> assertEquals(0, response.riskPenaltyOverride()),
                () -> assertEquals(
                        "評論樣本不足（少於 20 則），評論風險扣分計為 0",
                        response.statusMessage()),
                () -> assertFalse(response.fallbackApplied()));
    }

    @Test
    void agentFailureUsesSpecificationFallbackMessageInsteadOfSampleMessage() {
        Product product = Product.builder().id(104L).trackType(TrackType.A).build();
        ProductReview source = review(1L, product, LocalDate.of(2026, 8, 20));
        Fixture fixture = fixture(product, List.of(source), 20L);
        ReviewRiskInput input = new ReviewRiskInput(104L, List.of());
        when(fixture.sanitizer.sanitizeReviewRisk(eq(104L), anyList())).thenReturn(input);
        when(fixture.agent.analyze(input, 0, LocalDate.of(2026, 8, 20), false))
                .thenReturn(new ReviewRiskResult(
                        new ReviewRiskOutput(List.of(), List.of()),
                        true,
                        FallbackReason.AI_UNAVAILABLE,
                        false,
                        "rule-fallback",
                        "review-risk-v1"));

        var response = fixture.service.analyze(104L, false);

        assertAll(
                () -> assertFalse(response.analysisCompleted()),
                () -> assertEquals(0, response.riskPenaltyOverride()),
                () -> assertEquals("評論分析未完成", response.statusMessage()),
                () -> assertTrue(response.fallbackApplied()),
                () -> assertEquals("AI_UNAVAILABLE", response.fallbackReason()));
        verifyNoInteractions(fixture.analyses);
    }

    private static ReviewRiskResult success(List<ReviewRiskAnalysis> reviews) {
        return new ReviewRiskResult(
                new ReviewRiskOutput(reviews, List.of()),
                false,
                null,
                false,
                "fake/model",
                "review-risk-v1");
    }

    private static Fixture fixture(Long productId, List<ProductReview> source, long totalCount) {
        return fixture(Product.builder().id(productId).trackType(TrackType.A).build(), source, totalCount);
    }

    private static Fixture fixture(Product product, List<ProductReview> source, long totalCount) {
        ProductRepository products = mock(ProductRepository.class);
        ProductReviewRepository reviews = mock(ProductReviewRepository.class);
        ReviewAnalysisRepository analyses = mock(ReviewAnalysisRepository.class);
        PromptSanitizer sanitizer = mock(PromptSanitizer.class);
        ReviewRiskAgent agent = mock(ReviewRiskAgent.class);
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(reviews.countByProductId(product.getId())).thenReturn(totalCount);
        when(reviews.findByProductId(eq(product.getId()), any())).thenReturn(new PageImpl<>(source));
        return new Fixture(
                new ReviewRiskService(products, reviews, analyses, sanitizer, agent),
                sanitizer,
                agent,
                analyses);
    }

    private record Fixture(
            ReviewRiskService service,
            PromptSanitizer sanitizer,
            ReviewRiskAgent agent,
            ReviewAnalysisRepository analyses) {}

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
