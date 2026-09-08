package com.example.ssds.api.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.RecommendationAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.RecommendationPromptFactory;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecommendationServiceTest {
    @Test
    void sendsOnlyPercentilesAndBackendQuantityCandidatesThenPersistsInsight() {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductScoreRepository scoreRepository = mock(ProductScoreRepository.class);
        ItemFestivalAffinityRepository affinityRepository = mock(ItemFestivalAffinityRepository.class);
        FestivalCalendarRepository festivalRepository = mock(FestivalCalendarRepository.class);
        AiInsightRepository insightRepository = mock(AiInsightRepository.class);
        RecommendationAgent agent = mock(RecommendationAgent.class);
        Product product = Product.builder()
                .id(101L)
                .trackType(TrackType.A)
                .moq(200)
                .cost(new BigDecimal("50"))
                .suggestedPrice(new BigDecimal("100"))
                .build();
        List<ScoreFactor> factors = new ArrayList<>(List.of(
                factor(FactorCode.TREND, "96"),
                factor(FactorCode.MARGIN, "88"),
                factor(FactorCode.CVR, "90"),
                factor(FactorCode.PRICE_FIT, "82"),
                factor(FactorCode.FESTIVAL, "85"),
                factor(FactorCode.CLIMATE, "44")));
        factors.add(ScoreFactor.builder()
                .factorCode(FactorCode.LOGISTICS_RISK)
                .penalty(true)
                .penaltyValue(new BigDecimal("4.0"))
                .build());
        ProductScore score = ProductScore.builder()
                .sceneType(SceneType.VIRAL)
                .grade(Grade.B)
                .bonusSubtotal(new BigDecimal("86.89"))
                .penaltySubtotal(new BigDecimal("4.00"))
                .factors(factors)
                .build();
        ItemFestivalAffinity affinity = ItemFestivalAffinity.builder()
                .product(product)
                .festivalCode("MID_AUTUMN")
                .affinity(BigDecimal.ONE)
                .build();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        FestivalCalendar festival = FestivalCalendar.builder()
                .festivalCode("MID_AUTUMN")
                .festivalName("中秋節")
                .festivalDate(today.plusDays(30))
                .year((short) today.getYear())
                .build();
        RecommendationOutput output = new RecommendationOutput(
                DecisionType.ADOPT,
                200,
                300,
                "建議首批 200–300 件",
                "依正規化因子、分級與扣分規則，建議小量試單。");
        RecommendationResult result = new RecommendationResult(
                output, false, null, false, "mistral-small-latest",
                RecommendationPromptFactory.PROMPT_VERSION, 110, 35, 1);
        when(productRepository.findWithDetailsById(101L)).thenReturn(Optional.of(product));
        when(scoreRepository.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(101L))
                .thenReturn(Optional.of(score));
        when(affinityRepository.findByProductId(101L)).thenReturn(List.of(affinity));
        when(festivalRepository.findByFestivalDateBetweenOrderByFestivalDateAsc(
                eq(today), eq(today.plusDays(365))))
                .thenReturn(List.of(festival));
        when(agent.recommend(any(), eq(false))).thenReturn(result);
        RecommendationService service = new RecommendationService(
                productRepository,
                scoreRepository,
                affinityRepository,
                festivalRepository,
                insightRepository,
                new PromptSanitizer(),
                agent,
                new ObjectMapper());

        var response = service.recommend(101L, false);

        ArgumentCaptor<RecommendationInput> inputCaptor =
                ArgumentCaptor.forClass(RecommendationInput.class);
        verify(agent).recommend(inputCaptor.capture(), eq(false));
        RecommendationInput input = inputCaptor.getValue();
        assertEquals(6, input.factors().size());
        assertEquals(List.of(0, 200, 300), input.allowedQuantities());
        assertEquals(List.of(FactorCode.LOGISTICS_RISK), input.matchedPenaltyRules());
        assertEquals(30, input.festival().daysRemaining());
        assertTrue(input.factors().stream().allMatch(value -> value.percentile() != null));

        ArgumentCaptor<AiInsight> insightCaptor = ArgumentCaptor.forClass(AiInsight.class);
        verify(insightRepository).save(insightCaptor.capture());
        AiInsight insight = insightCaptor.getValue();
        assertEquals(InsightType.RECOMMENDATION, insight.getInsightType());
        assertEquals("MODEL_SHORT_GEN", insight.getModelAlias());
        assertEquals(RecommendationPromptFactory.PROMPT_VERSION, insight.getPromptVersion());
        assertEquals(1, insight.getRequestCount());
        assertEquals(DecisionType.ADOPT, response.action());
    }

    private static ScoreFactor factor(FactorCode code, String percentile) {
        return ScoreFactor.builder()
                .factorCode(code)
                .rawValue(new BigDecimal("9999"))
                .normalizedValue(new BigDecimal(percentile))
                .dataAvailable(true)
                .build();
    }
}
