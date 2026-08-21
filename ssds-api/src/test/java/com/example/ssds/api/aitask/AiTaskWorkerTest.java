package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AiTaskWorkerTest {
    @Test
    void completesTaskAfterSceneClassificationSucceeds() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(700L).taskType(AiTaskType.SCENE_CLASSIFY).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(701L).task(task).product(product).build();
        when(taskRepository.findById(700L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(700L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(
                taskRepository, itemRepository, sceneService, reviewRiskService);

        worker.run(new AiTaskCreatedEvent(700L, false));

        verify(sceneService).classify(101L, false);
        assertEquals(TaskStatus.SUCCESS, item.getStatus());
        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals(1, task.getSuccessCount());
        assertEquals(100, task.progressPercent());
    }

    @Test
    void reviewRiskFallbackKeepsTaskSuccessfulAndAddsIncompleteMarker() {
        AiTaskRepository taskRepository = mock(AiTaskRepository.class);
        AiTaskItemRepository itemRepository = mock(AiTaskItemRepository.class);
        SceneClassificationService sceneService = mock(SceneClassificationService.class);
        ReviewRiskService reviewRiskService = mock(ReviewRiskService.class);
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
                taskRepository, itemRepository, sceneService, reviewRiskService);

        worker.run(new AiTaskCreatedEvent(702L, true));

        assertEquals(TaskStatus.SUCCESS, item.getStatus());
        assertEquals("評論分析未完成", item.getErrorMessage());
        assertEquals(TaskStatus.SUCCESS, task.getStatus());
    }
}
