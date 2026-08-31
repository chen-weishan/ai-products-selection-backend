package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import com.example.ssds.ai.client.AiExecutionWarningContext;
import com.example.ssds.ai.client.AiModelUnavailableEvent;
import com.example.ssds.ai.client.SourcingConnectorQuotaExceededException;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AiTaskWorkerTest {
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
}
