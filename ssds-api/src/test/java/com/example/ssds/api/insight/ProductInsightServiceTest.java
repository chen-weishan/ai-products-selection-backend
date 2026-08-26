package com.example.ssds.api.insight;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.ProductInsightAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class ProductInsightServiceTest {
    @Test
    void sanitizesInputAndPersistsTwoInsightTypesFromOneRequest() {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductReviewRepository reviewRepository = mock(ProductReviewRepository.class);
        ProductScoreRepository scoreRepository = mock(ProductScoreRepository.class);
        AiInsightRepository insightRepository = mock(AiInsightRepository.class);
        ProductInsightAgent agent = mock(ProductInsightAgent.class);
        Product product = Product.builder()
                .id(101L)
                .name("抹茶餅乾")
                .category(Category.builder().id(10L).name("零食").build())
                .trackType(TrackType.A)
                .season(Season.ALL)
                .logisticsCondition("常溫")
                .cost(new BigDecimal("50"))
                .suggestedPrice(new BigDecimal("100"))
                .build();
        ProductReview review = ProductReview.builder()
                .id(1L)
                .product(product)
                .content("很好吃，聯絡電話 0912-345-678")
                .reviewedAt(LocalDate.of(2026, 8, 24))
                .build();
        ScoreFactor logistics = ScoreFactor.builder()
                .factorCode(FactorCode.LOGISTICS_RISK)
                .penalty(true)
                .penaltyValue(new BigDecimal("4.0"))
                .build();
        ProductScore score = ProductScore.builder().factors(List.of(logistics)).build();
        ProductInsightOutput output = new ProductInsightOutput(
                List.of(
                        new SellingPoint("口味獲得肯定", 1, "口味"),
                        new SellingPoint("資料不足：無足夠評論支持具體賣點", 0, "資料不足")),
                List.of(
                        new ProductInsightRisk("物流條件需留意", InsightRiskType.LOGISTICS, Severity.MEDIUM, true),
                        new ProductInsightRisk("資料不足：無足夠資料支持具體風險", InsightRiskType.OTHER, Severity.LOW, false)));
        ProductInsightResult result = new ProductInsightResult(
                output, false, null, false, "mistral-medium-latest", "product-insight-v1", 120, 40, 1);
        when(productRepository.findWithDetailsById(101L)).thenReturn(Optional.of(product));
        when(reviewRepository.findByProductId(eq(101L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)));
        when(reviewRepository.countByProductId(101L)).thenReturn(1L);
        when(scoreRepository.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(101L))
                .thenReturn(Optional.of(score));
        when(agent.analyze(any(), eq(0), eq(LocalDate.of(2026, 8, 24)), eq(false)))
                .thenReturn(result);
        ProductInsightService service = new ProductInsightService(
                productRepository,
                reviewRepository,
                scoreRepository,
                insightRepository,
                new PromptSanitizer(),
                agent,
                new ObjectMapper());

        var response = service.analyze(101L, false);

        ArgumentCaptor<ProductInsightInput> inputCaptor = ArgumentCaptor.forClass(ProductInsightInput.class);
        verify(agent).analyze(inputCaptor.capture(), eq(0), eq(LocalDate.of(2026, 8, 24)), eq(false));
        assertFalse(inputCaptor.getValue().reviews().getFirst().content().contains("0912-345-678"));
        assertEquals(List.of("LOGISTICS"), inputCaptor.getValue().penalties().get(1).matchedTopics());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiInsight>> insightsCaptor = ArgumentCaptor.forClass(List.class);
        verify(insightRepository).saveAll(insightsCaptor.capture());
        List<AiInsight> insights = insightsCaptor.getValue();
        assertEquals(List.of(InsightType.SELLING_POINT, InsightType.RISK),
                insights.stream().map(AiInsight::getInsightType).toList());
        assertEquals(1, insights.getFirst().getRequestCount());
        assertEquals(0, insights.get(1).getRequestCount());
        assertEquals("MODEL_LONG_TEXT", insights.getFirst().getModelAlias());
        assertEquals("product-insight-v1", insights.getFirst().getPromptVersion());
        assertTrue(response.analysisCompleted());
    }
}
