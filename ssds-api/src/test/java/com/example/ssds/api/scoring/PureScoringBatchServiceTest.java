package com.example.ssds.api.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PureScoringBatchServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final SceneClassificationLogRepository scenes = mock(SceneClassificationLogRepository.class);
    private final ScoringFactorBatchService factors = mock(ScoringFactorBatchService.class);
    private final ScoreExecutionService execution = mock(ScoreExecutionService.class);
    private final Instant attemptedAt = Instant.parse("2026-09-18T02:30:00Z");
    private PureScoringBatchService service;

    @BeforeEach
    void setUp() {
        service = new PureScoringBatchService(products, scenes, factors, execution);
    }

    @Test
    void preparesOnePopulationAndIsolatesFailureAndInsufficientResultsPerProduct() {
        List<Product> population = List.of(product(1L), product(2L), product(3L));
        when(products.findScorable(TrackType.A)).thenReturn(population);
        when(factors.preparePopulation(population, LocalDate.of(2026, 9, 18)))
                .thenReturn(prepared(population));
        when(scenes.findLatestByProductIds(List.of(1L, 2L, 3L))).thenReturn(List.of());
        when(execution.evaluatePrepared(any(), any())).thenAnswer(invocation -> {
            EvaluationCommand command = invocation.getArgument(0);
            if (command.productId() == 2L) {
                throw new IllegalStateException("product two failed");
            }
            if (command.productId() == 3L) {
                return result(LastScoringStatus.INSUFFICIENT_DATA, null, "insufficient");
            }
            return result(LastScoringStatus.SCORED, 701L, null);
        });

        PureScoringBatchService.BatchResult result = service.evaluateAll(attemptedAt);

        assertEquals(3, result.attemptedCount());
        assertEquals(1, result.scoredCount());
        assertEquals(1, result.insufficientCount());
        assertEquals(1, result.failedCount());
        assertEquals(2L, result.failures().getFirst().productId());
        verify(factors).preparePopulation(population, LocalDate.of(2026, 9, 18));
        verify(execution, times(3)).evaluatePrepared(any(), any());

        ArgumentCaptor<EvaluationCommand> commands = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(execution, times(3)).evaluatePrepared(commands.capture(), any());
        assertTrue(commands.getAllValues().stream().allMatch(command ->
                command.primaryScene() == SceneType.REPLENISHMENT
                        && command.alternativeScene() == null
                        && command.sceneConfidence() == null
                        && command.sceneFallbackApplied()));
    }

    @Test
    void usesLatestFinalSceneAndSuppressesAlternativeAfterManualOverride() {
        Product product = product(11L);
        SceneClassificationLog overridden = SceneClassificationLog.builder()
                .product(product)
                .finalSceneType(SceneType.FESTIVAL)
                .alternativeSceneType(SceneType.SEASONAL)
                .aiConfidence(new BigDecimal("0.60"))
                .overriddenBy(com.example.ssds.infra.entity.AppUser.builder().id(9L).build())
                .build();
        when(products.findScorable(TrackType.A)).thenReturn(List.of(product));
        when(factors.preparePopulation(any(), any())).thenReturn(prepared(List.of(product)));
        when(scenes.findLatestByProductIds(List.of(11L))).thenReturn(List.of(overridden));
        when(execution.evaluatePrepared(any(), any()))
                .thenReturn(result(LastScoringStatus.SCORED, 711L, null));

        service.evaluateAll(attemptedAt);

        ArgumentCaptor<EvaluationCommand> command = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(execution).evaluatePrepared(command.capture(), any());
        assertEquals(SceneType.FESTIVAL, command.getValue().primaryScene());
        assertEquals(null, command.getValue().alternativeScene());
        assertEquals(null, command.getValue().sceneConfidence());
        assertEquals(false, command.getValue().sceneFallbackApplied());
    }

    @Test
    void evaluatesFiveHundredProductsWithOneFactorPopulationPreparation() {
        List<Product> population = LongStream.rangeClosed(1, 500)
                .mapToObj(PureScoringBatchServiceTest::product)
                .toList();
        when(products.findScorable(TrackType.A)).thenReturn(population);
        when(factors.preparePopulation(any(), any())).thenReturn(prepared(population));
        when(scenes.findLatestByProductIds(any())).thenReturn(List.of());
        when(execution.evaluatePrepared(any(), any()))
                .thenReturn(result(LastScoringStatus.SCORED, 1L, null));

        PureScoringBatchService.BatchResult result = service.evaluateAll(attemptedAt);

        assertEquals(500, result.scoredCount());
        assertEquals(0, result.failedCount());
        verify(factors).preparePopulation(population, LocalDate.of(2026, 9, 18));
        verify(execution, times(500)).evaluatePrepared(any(), any());
    }

    @Test
    void importBatchSelectsOnlyAffectedScorableProductsWithoutCreatingAiWork() {
        Product first = product(1L);
        Product second = product(2L);
        when(products.findProductIdsByImportBatch(91L)).thenReturn(List.of(2L));
        when(products.findScorable(TrackType.A)).thenReturn(List.of(first, second));
        when(factors.preparePopulation(any(), any())).thenReturn(prepared(List.of(first, second)));
        when(scenes.findLatestByProductIds(List.of(2L))).thenReturn(List.of());
        when(execution.evaluatePrepared(any(), any()))
                .thenReturn(result(LastScoringStatus.SCORED, 702L, null));

        PureScoringBatchService.BatchResult result = service.evaluateImportBatch(91L, attemptedAt);

        assertEquals(1, result.attemptedCount());
        assertEquals(2L, result.results().getFirst().productId());
    }

    private static Product product(long id) {
        return Product.builder().id(id).build();
    }

    private static Map<Long, Map<FactorCode, FactorInput>> factorMap(List<Product> products) {
        Map<Long, Map<FactorCode, FactorInput>> values = new LinkedHashMap<>();
        products.forEach(product -> values.put(product.getId(), completeFactors()));
        return values;
    }

    private static PreparedPopulation prepared(List<Product> products) {
        Map<Long, Product> productsById = products.stream().collect(java.util.stream.Collectors.toMap(
                Product::getId, product -> product));
        return new PreparedPopulation(
                LocalDate.of(2026, 9, 18), productsById, Map.of(), factorMap(products));
    }

    private static Map<FactorCode, FactorInput> completeFactors() {
        EnumMap<FactorCode, FactorInput> values = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            values.put(code, code.isPenalty()
                    ? new FactorInput(BigDecimal.ZERO, null, BigDecimal.ZERO, true, false, null)
                    : new FactorInput(BigDecimal.ONE, BigDecimal.TEN, null, true, false, null));
        }
        return values;
    }

    private static EvaluationResult result(
            LastScoringStatus status, Long scoreId, String message) {
        return new EvaluationResult(status, "2026W38", 9L, scoreId, List.of(), message);
    }
}
