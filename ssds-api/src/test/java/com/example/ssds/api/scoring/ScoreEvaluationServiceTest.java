package com.example.ssds.api.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.score.ScoringResultRecorder;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationRequest;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

class ScoreEvaluationServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductScoreRepository scores = mock(ProductScoreRepository.class);
    private final WeightVersionRepository versions = mock(WeightVersionRepository.class);
    private final ScoringResultRecorder recorder = mock(ScoringResultRecorder.class);
    private final Product product = Product.builder().id(501L).build();
    private final WeightVersion version = versionWithProfiles();
    private final Instant attemptedAt = Instant.parse("2026-09-18T02:30:00Z");
    private ScoreEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new ScoreEvaluationService(products, scores, versions, recorder);
        when(products.findById(501L)).thenReturn(Optional.of(product));
        when(versions.findByIsCurrentTrue()).thenReturn(Optional.of(version));
        WeightVersionRepository.GradeThresholdView thresholds = mock(
                WeightVersionRepository.GradeThresholdView.class);
        when(thresholds.getGradeAMin()).thenReturn(new BigDecimal("80"));
        when(thresholds.getGradeBMin()).thenReturn(new BigDecimal("65"));
        when(versions.findGradeThreshold(eq(9L), anyString())).thenReturn(Optional.of(thresholds));
    }

    @Test
    void atomicallyCreatesPrimaryAndAlternativeSnapshotsWithExplicitIds() throws Exception {
        AtomicLong ids = new AtomicLong(700L);
        when(scores.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<ProductScore> saved = invocation.getArgument(0);
            saved.forEach(score -> score.setId(ids.getAndIncrement()));
            return saved;
        });

        EvaluationResult result = service.evaluate(new EvaluationRequest(
                501L,
                SceneType.FESTIVAL,
                SceneType.SEASONAL,
                88,
                attemptedAt,
                completeFactors()));

        assertEquals(LastScoringStatus.SCORED, result.status());
        assertEquals("2026W38", result.period());
        assertEquals(9L, result.weightVersionId());
        assertEquals(700L, result.primaryScoreId());
        assertEquals(2, result.snapshots().size());
        assertEquals(SceneType.FESTIVAL, result.snapshots().get(0).sceneType());
        assertTrue(result.snapshots().get(0).primary());
        assertEquals(SceneType.SEASONAL, result.snapshots().get(1).sceneType());
        ProductScore primary = capturedScores().get(0);
        assertEquals(9, primary.getFactors().size());
        assertEquals(301L, primary.getFactors().stream()
                .filter(factor -> factor.getFactorCode() == FactorCode.TREND)
                .findFirst().orElseThrow().getDrivingKeywordId());
        assertEquals(401L, primary.getFactors().stream()
                .filter(factor -> factor.getFactorCode() == FactorCode.FESTIVAL)
                .findFirst().orElseThrow().getDrivingFestivalId());
        verify(recorder).recordScored(product, attemptedAt);

        InOrder writes = inOrder(scores);
        writes.verify(scores).deactivateCurrentScores(501L, "2026W38");
        writes.verify(scores).saveAllAndFlush(anyList());
        assertNotNull(ScoreEvaluationService.class
                .getMethod("evaluate", EvaluationRequest.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void insufficientDataKeepsExistingScoreHistoryUntouched() {
        EvaluationResult result = service.evaluate(new EvaluationRequest(
                501L,
                SceneType.FESTIVAL,
                null,
                60,
                attemptedAt,
                insufficientFactors()));

        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, result.status());
        assertNull(result.primaryScoreId());
        assertTrue(result.snapshots().isEmpty());
        verify(recorder).recordInsufficientData(product, 2, attemptedAt);
        verify(scores, never()).deactivateCurrentScores(any(), anyString());
        verify(scores, never()).saveAllAndFlush(anyList());
    }

    @Test
    void persistenceFailureDoesNotRecordAFalseSuccess() {
        when(scores.saveAllAndFlush(anyList())).thenThrow(new IllegalStateException("write failed"));

        assertThrows(IllegalStateException.class, () -> service.evaluate(new EvaluationRequest(
                501L,
                SceneType.FESTIVAL,
                null,
                90,
                attemptedAt,
                completeFactors())));

        verify(recorder, never()).recordScored(any(), any());
    }

    @Test
    void rejectsAnIncompleteNineFactorContractBeforeAnyWrite() {
        Map<FactorCode, FactorInput> incomplete = new EnumMap<>(completeFactors());
        incomplete.remove(FactorCode.CLIMATE);

        assertThrows(IllegalArgumentException.class, () -> new EvaluationRequest(
                501L, SceneType.FESTIVAL, null, 90, attemptedAt, incomplete));
        verify(scores, never()).saveAllAndFlush(anyList());
    }

    @SuppressWarnings("unchecked")
    private List<ProductScore> capturedScores() {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(scores).saveAllAndFlush(captor.capture());
        return (List<ProductScore>) captor.getValue();
    }

    private static Map<FactorCode, FactorInput> completeFactors() {
        EnumMap<FactorCode, FactorInput> factors = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            factors.put(code, code.isPenalty()
                    ? new FactorInput(new BigDecimal("1"), null, BigDecimal.ZERO, true, false, "no risk")
                    : new FactorInput(new BigDecimal("1"), new BigDecimal("82"), null, true, false, "fixture"));
        }
        factors.put(FactorCode.TREND, new FactorInput(
                new BigDecimal("1"), new BigDecimal("82"), null, true, false, "trend", 301L, null));
        factors.put(FactorCode.FESTIVAL, new FactorInput(
                new BigDecimal("1"), new BigDecimal("82"), null, true, false, "festival", null, 401L));
        return factors;
    }

    private static Map<FactorCode, FactorInput> insufficientFactors() {
        EnumMap<FactorCode, FactorInput> factors = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            if (code.isPenalty()) {
                factors.put(code, new FactorInput(null, null, BigDecimal.ZERO, true, false, null));
            } else {
                boolean available = code == FactorCode.TREND || code == FactorCode.MARGIN;
                factors.put(code, new FactorInput(
                        null, available ? new BigDecimal("70") : null, null, available, false, null));
            }
        }
        return factors;
    }

    private static WeightVersion versionWithProfiles() {
        WeightVersion version = WeightVersion.builder().id(9L).build();
        List<WeightProfile> profiles = new ArrayList<>();
        for (SceneType scene : List.of(SceneType.FESTIVAL, SceneType.SEASONAL)) {
            for (FactorCode code : FactorCode.values()) {
                if (!code.isPenalty()) {
                    profiles.add(WeightProfile.builder()
                            .version(version)
                            .sceneType(scene)
                            .factorCode(code)
                            .weight(switch (code) {
                                case TREND, MARGIN -> new BigDecimal("0.18");
                                case CVR -> new BigDecimal("0.17");
                                case PRICE_FIT -> new BigDecimal("0.07");
                                case FESTIVAL -> new BigDecimal("0.30");
                                case CLIMATE -> new BigDecimal("0.10");
                                default -> throw new IllegalStateException();
                            })
                            .build());
                }
            }
        }
        version.setProfiles(profiles);
        return version;
    }
}
