package com.example.ssds.api.scene;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.SceneClassifierAgent;
import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.TrackAAiClient;
import com.example.ssds.ai.model.SceneClassifierInput;
import com.example.ssds.ai.model.SceneCode;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.SceneClassifierPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.SceneClassifierResponseParser;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SceneClassificationServiceTest {
    @Mock ProductRepository productRepository;
    @Mock HeatCompositeDailyRepository heatCompositeDailyRepository;
    @Mock ProductScoreRepository productScoreRepository;
    @Mock DecisionRecordRepository decisionRecordRepository;
    @Mock ItemFestivalAffinityRepository festivalAffinityRepository;
    @Mock SceneClassificationLogRepository logRepository;
    @Mock PromptSanitizer promptSanitizer;

    private SceneClassificationService service;

    @BeforeEach
    void setUp() {
        lenient().when(promptSanitizer.sanitizeSceneClassifier(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ObjectMapper mapper = new ObjectMapper();
        TrackAAiClient fakeClient = request -> new AiClientResponse("""
                {
                  "sceneType": "FESTIVAL",
                  "confidence": 0.88,
                  "reasoning": "品項具明確節慶關聯",
                  "alternativeScene": "REPLENISHMENT",
                  "signals": ["festival_match: MID_AUTUMN"]
                }
                """, "fake/model", 90, 20);
        SceneClassifierAgent agent = new SceneClassifierAgent(
                new AiAccessRouter(fakeClient),
                new SceneClassifierPromptFactory(mapper),
                new SceneClassifierResponseParser(mapper),
                mapper,
                "fake/model",
                "fake/fallback",
                3,
                7);
        service = new SceneClassificationService(
                productRepository,
                heatCompositeDailyRepository,
                productScoreRepository,
                decisionRecordRepository,
                festivalAffinityRepository,
                logRepository,
                promptSanitizer,
                agent);
    }

    @Test
    void trackAProductRunsFakeLlmAndPersistsValidatedResult() {
        Product product = product(101L, TrackType.A);
        TrendKeyword keyword = TrendKeyword.builder().id(301L).keyword("抹茶餅乾").build();
        product.setKeywords(new LinkedHashSet<>(List.of(keyword)));
        ScoreFactor trend = ScoreFactor.builder()
                .factorCode(FactorCode.TREND)
                .normalizedValue(new BigDecimal("96.00"))
                .dataAvailable(true)
                .build();
        ProductScore score = ProductScore.builder().factors(List.of(trend)).build();
        when(productRepository.findWithDetailsById(101L)).thenReturn(Optional.of(product));
        when(productScoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(101L))
                .thenReturn(Optional.of(score));
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(301L))
                .thenReturn(Optional.of(HeatCompositeDaily.builder()
                        .keyword(keyword)
                        .statDate(LocalDate.of(2026, 8, 29))
                        .compositeValue(new BigDecimal("91.00"))
                        .slope7d(new BigDecimal("0.25"))
                        .slope30d(new BigDecimal("0.40"))
                        .stage(HeatStage.RISING)
                        .build()));
        when(decisionRecordRepository.countByProductIdAndDecision(
                101L, com.example.ssds.core.domain.DecisionType.ADOPT)).thenReturn(2L);
        when(festivalAffinityRepository.findByProductId(101L)).thenReturn(List.of());
        when(logRepository.save(any())).thenAnswer(invocation -> {
            SceneClassificationLog log = invocation.getArgument(0);
            log.setId(501L);
            return log;
        });

        var response = service.classify(101L, false);

        assertEquals(501L, response.classificationId());
        assertEquals(SceneCode.FESTIVAL, response.sceneType());
        assertEquals("MODEL_CLASSIFY", response.modelAlias());
        assertFalse(response.fallbackApplied());
        ArgumentCaptor<SceneClassifierInput> inputCaptor = ArgumentCaptor.forClass(SceneClassifierInput.class);
        verify(promptSanitizer).sanitizeSceneClassifier(inputCaptor.capture());
        assertEquals(new BigDecimal("96.00"), inputCaptor.getValue().heatSlopePercentile());
        assertEquals(new BigDecimal("0.25"), inputCaptor.getValue().heatSlope7d());
        assertEquals(new BigDecimal("0.40"), inputCaptor.getValue().heatSlope30d());
        assertEquals(2L, inputCaptor.getValue().historicalCampaignCount());
        assertEquals(HeatStage.RISING, inputCaptor.getValue().heatStage());
        verify(logRepository).save(argThat(log ->
                log.getFinalSceneType().name().equals("FESTIVAL")
                        && log.getSignals().size() == 1
                        && log.getPeriod() != null
                        && log.getPeriod().matches("\\d{4}W(0[1-9]|[1-4]\\d|5[0-3])")));
    }

    @Test
    void trackBProductIsRejectedBeforeCallingLlm() {
        when(productRepository.findWithDetailsById(120L)).thenReturn(Optional.of(product(120L, TrackType.B)));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.classify(120L, false));

        assertTrue(exception.getMessage().contains("僅接受 A 軌"));
        verifyNoInteractions(logRepository);
    }

    @Test
    void multipleKeywordsUseHighestCompositeFromNewestAvailableDate() {
        Product product = product(130L, TrackType.A);
        TrendKeyword lower = TrendKeyword.builder().id(301L).keyword("低熱度").build();
        TrendKeyword higher = TrendKeyword.builder().id(302L).keyword("高熱度").build();
        TrendKeyword stale = TrendKeyword.builder().id(303L).keyword("過期高熱度").build();
        product.setKeywords(new LinkedHashSet<>(List.of(lower, higher, stale)));
        stubClassificationDependencies(product);
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(301L))
                .thenReturn(Optional.of(heat(lower, LocalDate.of(2026, 9, 1), "40", HeatStage.PLATEAU)));
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(302L))
                .thenReturn(Optional.of(heat(higher, LocalDate.of(2026, 9, 1), "80", HeatStage.RISING)));
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(303L))
                .thenReturn(Optional.of(heat(stale, LocalDate.of(2026, 8, 31), "99", HeatStage.DECLINING)));

        service.classify(130L, false);

        ArgumentCaptor<SceneClassifierInput> input = ArgumentCaptor.forClass(SceneClassifierInput.class);
        verify(promptSanitizer).sanitizeSceneClassifier(input.capture());
        assertEquals(HeatStage.RISING, input.getValue().heatStage());
    }

    @Test
    void multipleKeywordsPutNullCompositeLastAndBreakExactTiesByLowestKeywordId() {
        Product product = product(131L, TrackType.A);
        TrendKeyword nullValue = TrendKeyword.builder().id(303L).keyword("null").build();
        TrendKeyword higherId = TrendKeyword.builder().id(302L).keyword("同值二").build();
        TrendKeyword lowerId = TrendKeyword.builder().id(301L).keyword("同值一").build();
        product.setKeywords(new LinkedHashSet<>(List.of(nullValue, higherId, lowerId)));
        stubClassificationDependencies(product);
        LocalDate date = LocalDate.of(2026, 9, 1);
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(303L))
                .thenReturn(Optional.of(heat(nullValue, date, null, HeatStage.DECLINING)));
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(302L))
                .thenReturn(Optional.of(heat(higherId, date, "80", HeatStage.PLATEAU)));
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(301L))
                .thenReturn(Optional.of(heat(lowerId, date, "80", HeatStage.RISING)));

        service.classify(131L, false);

        ArgumentCaptor<SceneClassifierInput> input = ArgumentCaptor.forClass(SceneClassifierInput.class);
        verify(promptSanitizer).sanitizeSceneClassifier(input.capture());
        assertEquals(HeatStage.RISING, input.getValue().heatStage());
    }

    @Test
    void productWithoutKeywordHeatUsesUnknownBucketAndNullStage() {
        Product product = product(132L, TrackType.A);
        product.setKeywords(new LinkedHashSet<>(List.of(
                TrendKeyword.builder().id(304L).keyword("無熱度").build())));
        stubClassificationDependencies(product);
        when(heatCompositeDailyRepository.findFirstByKeywordIdOrderByStatDateDesc(304L))
                .thenReturn(Optional.empty());

        service.classify(132L, false);

        ArgumentCaptor<SceneClassifierInput> input = ArgumentCaptor.forClass(SceneClassifierInput.class);
        verify(promptSanitizer).sanitizeSceneClassifier(input.capture());
        assertNull(input.getValue().heatStage());
        assertEquals(com.example.ssds.ai.model.HeatBucket.UNKNOWN, input.getValue().heatBucket());
    }

    private void stubClassificationDependencies(Product product) {
        when(productRepository.findWithDetailsById(product.getId())).thenReturn(Optional.of(product));
        when(productScoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(product.getId()))
                .thenReturn(Optional.empty());
        when(decisionRecordRepository.countByProductIdAndDecision(
                product.getId(), com.example.ssds.core.domain.DecisionType.ADOPT)).thenReturn(0L);
        when(festivalAffinityRepository.findByProductId(product.getId())).thenReturn(List.of());
        when(logRepository.save(any())).thenAnswer(invocation -> {
            SceneClassificationLog log = invocation.getArgument(0);
            log.setId(500L + product.getId());
            return log;
        });
    }

    private static HeatCompositeDaily heat(
            TrendKeyword keyword, LocalDate date, String compositeValue, HeatStage stage) {
        return HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(date)
                .compositeValue(compositeValue == null ? null : new BigDecimal(compositeValue))
                .slope7d(new BigDecimal("0.10"))
                .slope30d(new BigDecimal("0.20"))
                .stage(stage)
                .build();
    }

    private static Product product(Long id, TrackType trackType) {
        Category category = Category.builder().id(10L).name("進口零食").build();
        return Product.builder()
                .id(id)
                .name("測試商品")
                .category(category)
                .season(Season.ALL)
                .trackType(trackType)
                .keywords(new LinkedHashSet<>())
                .build();
    }
}
