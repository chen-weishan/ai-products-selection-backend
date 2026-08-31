package com.example.ssds.api.scene;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.SceneClassifierAgent;
import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.SceneClassifierInput;
import com.example.ssds.ai.model.SceneCode;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.SceneClassifierPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
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
    @Mock HeatReadingRepository heatReadingRepository;
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
                heatReadingRepository,
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
                        .stage(HeatStage.RISING)
                        .build()));
        when(decisionRecordRepository.countByProductId(101L)).thenReturn(2L);
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
