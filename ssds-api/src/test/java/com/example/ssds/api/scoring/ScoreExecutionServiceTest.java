package com.example.ssds.api.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationRequest;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScoreExecutionServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final ScoringFactorBatchService factorBatch = mock(ScoringFactorBatchService.class);
    private final ScoreEvaluationService evaluation = mock(ScoreEvaluationService.class);
    private final Product target = Product.builder().id(501L).build();
    private final Product peer = Product.builder().id(502L).build();
    private final Instant attemptedAt = Instant.parse("2026-09-18T02:30:00Z");
    private ScoreExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ScoreExecutionService(products, factorBatch, evaluation);
        when(products.findScorable(TrackType.A)).thenReturn(List.of(target, peer));
    }

    @Test
    void preparesTheWholePopulationThenEvaluatesTheRequestedProduct() {
        Map<FactorCode, FactorInput> factors = completeFactors();
        when(factorBatch.prepare(any(), eq(LocalDate.of(2026, 9, 18))))
                .thenReturn(Map.of(501L, factors, 502L, completeFactors()));
        EvaluationResult expected = new EvaluationResult(
                LastScoringStatus.SCORED, "2026W38", 9L, 701L, List.of(), null);
        when(evaluation.evaluate(any())).thenReturn(expected);

        EvaluationResult actual = service.evaluate(new EvaluationCommand(
                501L, SceneType.FESTIVAL, SceneType.SEASONAL, new BigDecimal("0.88"), attemptedAt));

        assertEquals(expected, actual);
        verify(factorBatch).prepare(List.of(target, peer), LocalDate.of(2026, 9, 18));
        ArgumentCaptor<EvaluationRequest> request = ArgumentCaptor.forClass(EvaluationRequest.class);
        verify(evaluation).evaluate(request.capture());
        assertEquals(501L, request.getValue().productId());
        assertEquals(SceneType.FESTIVAL, request.getValue().primaryScene());
        assertEquals(SceneType.SEASONAL, request.getValue().alternativeScene());
        assertEquals(100, request.getValue().confidence());
        assertEquals(factors, request.getValue().factors());
    }

    @Test
    void evaluatesWithPreparedPopulationByRefreshingOnlyTheTarget() {
        PreparedPopulation population = mock(PreparedPopulation.class);
        Map<FactorCode, FactorInput> factors = completeFactors();
        when(factorBatch.refreshTarget(population, 501L)).thenReturn(factors);
        EvaluationResult expected = new EvaluationResult(
                LastScoringStatus.SCORED, "2026W38", 9L, 701L, List.of(), null);
        when(evaluation.evaluate(any())).thenReturn(expected);

        EvaluationResult actual = service.evaluate(new EvaluationCommand(
                501L, SceneType.FESTIVAL, null, new BigDecimal("0.88"), attemptedAt), population);

        assertEquals(expected, actual);
        verify(factorBatch).refreshTarget(population, 501L);
        verify(factorBatch, never()).prepare(any(), any());
    }

    @Test
    void rejectsAProductOutsideTheScorablePopulationBeforeLoadingEvidence() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(new EvaluationCommand(
                999L, SceneType.REPLENISHMENT, null, new BigDecimal("0.70"), attemptedAt)));

        verify(factorBatch, never()).prepare(any(), any());
        verify(evaluation, never()).evaluate(any());
    }

    @Test
    void deductsConfidenceForUnavailableImputedAndLowConfidenceSceneInputs() {
        Map<FactorCode, FactorInput> factors = new EnumMap<>(completeFactors());
        factors.put(FactorCode.CLIMATE, new FactorInput(null, null, null, false, false, "missing"));
        factors.put(FactorCode.CVR, new FactorInput(
                BigDecimal.ONE, BigDecimal.TEN, null, true, true, "imputed"));
        when(factorBatch.prepare(any(), any())).thenReturn(Map.of(501L, factors));
        when(evaluation.evaluate(any())).thenReturn(new EvaluationResult(
                LastScoringStatus.SCORED, "2026W38", 9L, 701L, List.of(), null));

        service.evaluate(new EvaluationCommand(
                501L, SceneType.FESTIVAL, null, new BigDecimal("0.69"), attemptedAt));

        ArgumentCaptor<EvaluationRequest> request = ArgumentCaptor.forClass(EvaluationRequest.class);
        verify(evaluation).evaluate(request.capture());
        assertEquals(78, request.getValue().confidence());
    }

    @Test
    void deductsConfidenceForSceneFallbackWithoutInventingAiConfidence() {
        Map<FactorCode, FactorInput> factors = completeFactors();
        when(factorBatch.prepare(any(), any())).thenReturn(Map.of(501L, factors));
        when(evaluation.evaluate(any())).thenReturn(new EvaluationResult(
                LastScoringStatus.SCORED, "2026W38", 9L, 701L, List.of(), null));

        service.evaluate(new EvaluationCommand(
                501L, SceneType.REPLENISHMENT, null, null, true, attemptedAt));

        ArgumentCaptor<EvaluationRequest> request = ArgumentCaptor.forClass(EvaluationRequest.class);
        verify(evaluation).evaluate(request.capture());
        assertEquals(90, request.getValue().confidence());
    }

    @Test
    void manualSceneWithoutAiConfidenceDoesNotReceiveFallbackDeduction() {
        Map<FactorCode, FactorInput> factors = completeFactors();
        when(factorBatch.prepare(any(), any())).thenReturn(Map.of(501L, factors));
        when(evaluation.evaluate(any())).thenReturn(new EvaluationResult(
                LastScoringStatus.SCORED, "2026W38", 9L, 701L, List.of(), null));

        service.evaluate(new EvaluationCommand(
                501L, SceneType.FESTIVAL, null, null, false, attemptedAt));

        ArgumentCaptor<EvaluationRequest> request = ArgumentCaptor.forClass(EvaluationRequest.class);
        verify(evaluation).evaluate(request.capture());
        assertEquals(100, request.getValue().confidence());
    }

    private static Map<FactorCode, FactorInput> completeFactors() {
        EnumMap<FactorCode, FactorInput> factors = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            factors.put(code, code.isPenalty()
                    ? new FactorInput(BigDecimal.ZERO, null, BigDecimal.ZERO, true, false, null)
                    : new FactorInput(BigDecimal.ONE, BigDecimal.TEN, null, true, false, null));
        }
        return factors;
    }
}
