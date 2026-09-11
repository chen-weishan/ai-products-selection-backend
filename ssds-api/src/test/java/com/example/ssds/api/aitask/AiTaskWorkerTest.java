package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.calibration.WeightCalibrationService;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.recommendation.dto.RecommendationResponse;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.api.sourcing.dto.SourcingScoutResponse;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import com.example.ssds.ai.client.AiExecutionWarningContext;
import com.example.ssds.ai.client.AiModelUnavailableEvent;
import com.example.ssds.ai.client.SourcingConnectorQuotaExceededException;
import com.example.ssds.ai.client.DailyAiBudget;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiTaskWorkerTest {
    @ParameterizedTest
    @EnumSource(value = AiTaskType.class, names = {
            "SCENE_CLASSIFY", "REVIEW_RISK", "SELLING_POINT",
            "RECOMMENDATION", "TREND_INTERPRET", "SOURCING_SCOUT"})
    void cacheHitIsPersistedWithoutConsumingRequestQuota(AiTaskType taskType) {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        TrendInterpretationService trendService = mock(TrendInterpretationService.class);
        SourcingScoutService sourcingService = mock(SourcingScoutService.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        Product product = Product.builder().id(101L).build();
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("抹茶").build();
        AiTask task = AiTask.builder().id(730L).taskType(taskType)
                .budgetPool(taskType == AiTaskType.SOURCING_SCOUT
                        ? AiTaskType.BudgetPool.TRACK_B : AiTaskType.BudgetPool.TRACK_A)
                .totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(731L).task(task)
                .product(taskType == AiTaskType.TREND_INTERPRET ? null : product)
                .keyword(taskType == AiTaskType.TREND_INTERPRET ? keyword : null)
                .build();
        when(taskRepository.findById(730L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(730L)).thenReturn(List.of(item));
        switch (taskType) {
            case SCENE_CLASSIFY -> {
                SceneClassificationResponse response = mock(SceneClassificationResponse.class);
                when(response.cacheHit()).thenReturn(true);
                when(sceneService.classify(101L, false)).thenReturn(response);
            }
            case REVIEW_RISK -> {
                ReviewRiskResponse response = mock(ReviewRiskResponse.class);
                when(response.cacheHit()).thenReturn(true);
                when(reviewRiskService.analyze(101L, false)).thenReturn(response);
            }
            case SELLING_POINT -> {
                ProductInsightResponse response = mock(ProductInsightResponse.class);
                when(response.cacheHit()).thenReturn(true);
                when(response.analysisCompleted()).thenReturn(true);
                when(productInsightService.analyze(101L, false)).thenReturn(response);
            }
            case RECOMMENDATION -> {
                RecommendationResponse response = mock(RecommendationResponse.class);
                when(response.cacheHit()).thenReturn(true);
                when(recommendationService.recommend(101L, false)).thenReturn(response);
            }
            case TREND_INTERPRET -> {
                TrendInterpretationResponse response = mock(TrendInterpretationResponse.class);
                when(response.cacheHit()).thenReturn(true);
                when(trendService.interpret(31L, false)).thenReturn(response);
            }
            case SOURCING_SCOUT -> {
                SourcingScoutResponse response = mock(SourcingScoutResponse.class);
                when(response.cacheHit()).thenReturn(true);
                when(sourcingService.scout(101L, false)).thenReturn(response);
            }
            default -> throw new IllegalArgumentException("unexpected task type");
        }
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService, trendService, sourcingService,
                null, budget, 150);

        worker.run(new AiTaskCreatedEvent(730L, false));

        assertEquals(TaskItemStatus.SKIPPED_CACHE, item.getStatus());
        assertEquals(0, task.getRequestCount());
        assertEquals(0, task.getRetryPoolRequestCount());
        assertEquals(1, task.getCacheHitCount());
    }

    @Test
    void sourcingRequestsAndRetriesArePersistedInTheirRespectivePools() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        TrendInterpretationService trendService = mock(TrendInterpretationService.class);
        SourcingScoutService sourcingService = mock(SourcingScoutService.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder().id(732L).taskType(AiTaskType.SOURCING_SCOUT)
                .budgetPool(AiTaskType.BudgetPool.TRACK_B).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(733L).task(task).product(product).build();
        when(taskRepository.findById(732L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(732L)).thenReturn(List.of(item));
        doAnswer(ignored -> {
            budget.acquire(AiTaskType.BudgetPool.TRACK_B, false);
            budget.acquire(AiTaskType.BudgetPool.TRACK_B, true);
            return mock(SourcingScoutResponse.class);
        }).when(sourcingService).scout(101L, true);
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService, trendService, sourcingService,
                null, budget, 150);

        worker.run(new AiTaskCreatedEvent(732L, true));

        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals(2, task.getRequestCount());
        assertEquals(1, task.getRetryPoolRequestCount());
        assertEquals(0, task.getCacheHitCount());
    }

    @Test
    void sourcingConnectorQuotaMarksBackgroundTaskFailedWithSafeMessage() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        TrendInterpretationService trendService = mock(TrendInterpretationService.class);
        SourcingScoutService sourcingService = mock(SourcingScoutService.class);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(712L).taskType(AiTaskType.SOURCING_SCOUT).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(713L).task(task).product(product).build();
        when(taskRepository.findById(712L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(712L)).thenReturn(List.of(item));
        when(sourcingService.scout(101L, true))
                .thenThrow(new SourcingConnectorQuotaExceededException(null));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService, trendService, sourcingService);

        worker.run(new AiTaskCreatedEvent(712L, true));

        verify(sourcingService).scout(101L, true);
        assertEquals(TaskItemStatus.FAILED, item.getStatus());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("B 軌尋源 Connector 額度已達上限，請於服務額度重置後再試", item.getErrorMessage());
    }

    @Test
    void completesTaskAfterSceneClassificationSucceeds() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(700L).taskType(AiTaskType.SCENE_CLASSIFY).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(701L).task(task).product(product).build();
        when(taskRepository.findById(700L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(700L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService);

        worker.run(new AiTaskCreatedEvent(700L, false));

        verify(sceneService).classify(101L, false);
        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
        assertEquals(1, task.getSuccessCount());
        assertEquals(100, task.progressPercent());
    }

    @Test
    void reviewRiskFallbackKeepsTaskSuccessfulAndAddsIncompleteMarker() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        ReviewRiskResponse fallback = mock(ReviewRiskResponse.class);
        when(fallback.fallbackApplied()).thenReturn(true);
        when(fallback.statusMessage()).thenReturn("評論分析未完成");
        when(reviewRiskService.analyze(101L, true)).thenReturn(fallback);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(702L).taskType(AiTaskType.REVIEW_RISK).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(703L).task(task).product(product).build();
        when(taskRepository.findById(702L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(702L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService);

        worker.run(new AiTaskCreatedEvent(702L, true));

        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals("評論分析未完成", item.getErrorMessage());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void successfulFallbackKeepsTaskSuccessfulAndShowsModelConfigurationWarning() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(710L).taskType(AiTaskType.SCENE_CLASSIFY).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(711L).task(task).product(product).build();
        when(taskRepository.findById(710L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(710L)).thenReturn(List.of(item));
        doAnswer(invocation -> {
            AiExecutionWarningContext.record(new AiModelUnavailableEvent(
                    "MODEL_CLASSIFY", "removed-model", 404));
            return null;
        }).when(sceneService).classify(101L, false);
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService);

        worker.run(new AiTaskCreatedEvent(710L, false));

        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                item.getErrorMessage().contains("請更新模型設定"));
    }

    @Test
    void productInsightFallbackKeepsTaskSuccessfulAndAddsIncompleteMarker() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        ProductInsightResponse fallback = mock(ProductInsightResponse.class);
        when(fallback.analysisCompleted()).thenReturn(false);
        when(fallback.statusMessage()).thenReturn("賣點與風險分析未完成");
        when(productInsightService.analyze(101L, true)).thenReturn(fallback);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(704L).taskType(AiTaskType.SELLING_POINT).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(705L).task(task).product(product).build();
        when(taskRepository.findById(704L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(704L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService);

        worker.run(new AiTaskCreatedEvent(704L, true));

        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals("賣點與風險分析未完成", item.getErrorMessage());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void recommendationTaskInvokesAgentFourAndCompletes() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(706L).taskType(AiTaskType.RECOMMENDATION).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(707L).task(task).product(product).build();
        when(taskRepository.findById(706L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(706L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService);

        worker.run(new AiTaskCreatedEvent(706L, true));

        verify(recommendationService).recommend(101L, true);
        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void trendTaskUsesKeywordTargetAndCompletes() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        TrendInterpretationService trendService = mock(TrendInterpretationService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("抹茶").build();
        AiTask task = AiTask.builder()
                .id(708L).taskType(AiTaskType.TREND_INTERPRET).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(709L).task(task).keyword(keyword).build();
        when(taskRepository.findById(708L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(708L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService, trendService);

        worker.run(new AiTaskCreatedEvent(708L, true));

        verify(trendService).interpret(31L, true);
        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void calibrationTaskUsesReportTargetAndCompletes() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        TrendInterpretationService trendService = mock(TrendInterpretationService.class);
        SourcingScoutService sourcingService = mock(SourcingScoutService.class);
        WeightCalibrationService calibrationService = mock(WeightCalibrationService.class);
        CalibrationReport report = CalibrationReport.builder().id(7L).quarter("2026Q3").build();
        AiTask task = AiTask.builder().id(714L)
                .taskType(AiTaskType.WEIGHT_CALIBRATION)
                .budgetPool(AiTaskType.BudgetPool.RETRY)
                .totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(715L).task(task)
                .calibrationReport(report).build();
        when(taskRepository.findById(714L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(714L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService, trendService, sourcingService,
                calibrationService);

        worker.run(new AiTaskCreatedEvent(714L, true));

        verify(calibrationService).interpret(7L, true);
        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, task.getStatus());
    }

    @Test
    void fullAnalysisStopsAtItemCapAndPersistsRequestCount() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        ProductInsightService productInsightService = mock(ProductInsightService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);
        FullAnalysisOrchestrator orchestrator = mock(FullAnalysisOrchestrator.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        Product firstProduct = Product.builder().id(101L).build();
        Product secondProduct = Product.builder().id(102L).build();
        AiTask task = AiTask.builder()
                .id(720L)
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .totalCount(2)
                .build();
        AiTaskItem first = AiTaskItem.builder().id(721L).task(task).product(firstProduct).build();
        AiTaskItem second = AiTaskItem.builder().id(722L).task(task).product(secondProduct).build();
        when(taskRepository.findById(720L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(720L)).thenReturn(List.of(first, second));
        when(orchestrator.analyze(101L, false)).thenAnswer(ignored -> {
            for (int index = 0; index < 4; index++) budget.acquire(AiTaskType.BudgetPool.TRACK_A);
            return new FullAnalysisOrchestrator.Result(0, "");
        });
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService,
                productInsightService, recommendationService, null, null,
                orchestrator, budget, 1);

        worker.run(new AiTaskCreatedEvent(720L, false));

        verify(orchestrator).analyze(101L, false);
        verify(orchestrator, never()).analyze(102L, false);
        assertEquals(TaskItemStatus.SUCCEEDED, first.getStatus());
        assertEquals(TaskItemStatus.SKIPPED_QUOTA, second.getStatus());
        assertEquals(4, task.getRequestCount());
        assertEquals(TaskStatus.PARTIAL, task.getStatus());
    }

    @Test
    void fullAnalysisPersistsActualAttemptsIncludingRetriesInsteadOfFixedStageCount() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        FullAnalysisOrchestrator orchestrator = mock(FullAnalysisOrchestrator.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        AiTask task = AiTask.builder()
                .id(740L)
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .totalCount(1)
                .build();
        AiTaskItem item = AiTaskItem.builder()
                .id(741L)
                .task(task)
                .product(Product.builder().id(101L).build())
                .build();
        when(taskRepository.findById(740L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(740L)).thenReturn(List.of(item));
        when(orchestrator.analyze(101L, false)).thenAnswer(ignored -> {
            for (int index = 0; index < 4; index++) {
                budget.acquire(AiTaskType.BudgetPool.TRACK_A, false);
            }
            budget.acquire(AiTaskType.BudgetPool.TRACK_A, true);
            budget.acquire(AiTaskType.BudgetPool.TRACK_A, true);
            return new FullAnalysisOrchestrator.Result(0, "");
        });
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, mock(SceneClassificationService.class),
                mock(ReviewRiskService.class), mock(ProductInsightService.class),
                mock(RecommendationService.class), null, null, orchestrator, budget, 150);

        worker.run(new AiTaskCreatedEvent(740L, false));

        assertAll(
                () -> assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus()),
                () -> assertEquals(6, task.getRequestCount()),
                () -> assertEquals(2, task.getRetryPoolRequestCount()),
                () -> assertEquals(0, task.getCacheHitCount()));
    }

    @Test
    void fullAnalysisCanCompleteWithCacheHitsAndFewerThanFourExternalRequests() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        FullAnalysisOrchestrator orchestrator = mock(FullAnalysisOrchestrator.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        AiTask task = AiTask.builder()
                .id(742L)
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .totalCount(1)
                .build();
        AiTaskItem item = AiTaskItem.builder()
                .id(743L)
                .task(task)
                .product(Product.builder().id(101L).build())
                .build();
        when(taskRepository.findById(742L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(742L)).thenReturn(List.of(item));
        when(orchestrator.analyze(101L, false)).thenAnswer(ignored -> {
            budget.acquire(AiTaskType.BudgetPool.TRACK_A, false);
            budget.acquire(AiTaskType.BudgetPool.TRACK_A, false);
            return new FullAnalysisOrchestrator.Result(2, "");
        });
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, mock(SceneClassificationService.class),
                mock(ReviewRiskService.class), mock(ProductInsightService.class),
                mock(RecommendationService.class), null, null, orchestrator, budget, 150);

        worker.run(new AiTaskCreatedEvent(742L, false));

        assertAll(
                () -> assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus()),
                () -> assertEquals(2, task.getRequestCount()),
                () -> assertEquals(2, task.getCacheHitCount()),
                () -> assertEquals(0, task.getRetryPoolRequestCount()));
    }
}
