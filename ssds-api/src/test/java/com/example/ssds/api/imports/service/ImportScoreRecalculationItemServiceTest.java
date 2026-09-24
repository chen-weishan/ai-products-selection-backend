package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreExecutionService;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class ImportScoreRecalculationItemServiceTest {

    private ProductRepository products;
    private SceneClassificationLogRepository scenes;
    private ScoreExecutionService scoring;
    private ImportScoreRecalculationItemService service;
    private Product product;

    @BeforeEach
    void setUp() {
        products = org.mockito.Mockito.mock(ProductRepository.class);
        scenes = org.mockito.Mockito.mock(SceneClassificationLogRepository.class);
        scoring = org.mockito.Mockito.mock(ScoreExecutionService.class);
        service = new ImportScoreRecalculationItemService(products, scenes, scoring);
        product = Product.builder().id(10L).name("奶茶")
                .category(Category.builder().id(3L).build())
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .build();
        when(products.findWithDetailsById(10L)).thenReturn(Optional.of(product));
        when(scoring.evaluate(any(EvaluationCommand.class))).thenReturn(new EvaluationResult(
                LastScoringStatus.SCORED, "2026W39", 1L, 2L, List.of(), null));
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"DRAFT", "REJECTED"})
    void skipsProductsWhoseStatusMustNotBeScored(ProductStatus status) {
        product.setStatus(status);

        assertThat(service.recalculate(10L))
                .isEqualTo(ImportScoreRecalculationItemService.Result.SKIPPED);

        verify(scenes, never()).findFirstByProductIdOrderByCreatedAtDesc(any());
        verify(scoring, never()).evaluate(any(EvaluationCommand.class));
        assertThat(product.getLastScoringStatus()).isNull();
        assertThat(product.getLastScoringAttemptedAt()).isNull();
    }

    @Test
    void reusesLatestSceneWithoutRunningAnyClassifier() {
        when(scenes.findFirstByProductIdOrderByCreatedAtDesc(10L)).thenReturn(Optional.of(
                SceneClassificationLog.builder().finalSceneType(SceneType.FESTIVAL).build()));

        assertThat(service.recalculate(10L))
                .isEqualTo(ImportScoreRecalculationItemService.Result.SCORED);

        ArgumentCaptor<EvaluationCommand> command = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(scoring).evaluate(command.capture());
        assertThat(command.getValue().productId()).isEqualTo(10L);
        assertThat(command.getValue().primaryScene()).isEqualTo(SceneType.FESTIVAL);
        verify(scenes, never()).save(any());
        assertThat(product.getLastScoringStatus()).isEqualTo(LastScoringStatus.SCORED);
    }

    @Test
    void recordsReplenishmentFallbackWhenNoPreviousSceneExists() {
        when(scenes.findFirstByProductIdOrderByCreatedAtDesc(10L)).thenReturn(Optional.empty());

        service.recalculate(10L);

        ArgumentCaptor<EvaluationCommand> command = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(scoring).evaluate(command.capture());
        assertThat(command.getValue().primaryScene()).isEqualTo(SceneType.REPLENISHMENT);
        assertThat(command.getValue().sceneFallbackApplied()).isTrue();
        ArgumentCaptor<SceneClassificationLog> captor =
                ArgumentCaptor.forClass(SceneClassificationLog.class);
        verify(scenes).save(captor.capture());
        assertThat(captor.getValue().isFallbackApplied()).isTrue();
        assertThat(captor.getValue().getFallbackReason()).isEqualTo("IMPORT_NO_PREVIOUS_SCENE");
        assertThat(captor.getValue().getFinalSceneType()).isEqualTo(SceneType.REPLENISHMENT);
    }

    @Test
    void insufficientDataIsRecordedWithoutCreatingAPlaceholderScore() {
        when(scenes.findFirstByProductIdOrderByCreatedAtDesc(10L)).thenReturn(Optional.empty());
        when(scoring.evaluate(any(EvaluationCommand.class))).thenReturn(new EvaluationResult(
                LastScoringStatus.INSUFFICIENT_DATA,
                "2026W39", 1L, null, List.of(), "正式九因子資料不足"));

        assertThat(service.recalculate(10L))
                .isEqualTo(ImportScoreRecalculationItemService.Result.INSUFFICIENT_DATA);
        assertThat(product.getLastScoringStatus()).isEqualTo(LastScoringStatus.INSUFFICIENT_DATA);
        verify(scenes, never()).save(any());
    }
}
