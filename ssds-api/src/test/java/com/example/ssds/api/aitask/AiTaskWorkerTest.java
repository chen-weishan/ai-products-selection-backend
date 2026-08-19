package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.example.ssds.api.scene.SceneClassificationService;
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
        Product product = Product.builder().id(101L).build();
        AiTask task = AiTask.builder()
                .id(700L).taskType(AiTaskType.SCENE_CLASSIFY).totalCount(1).build();
        AiTaskItem item = AiTaskItem.builder().id(701L).task(task).product(product).build();
        when(taskRepository.findById(700L)).thenReturn(Optional.of(task));
        when(itemRepository.findByTaskId(700L)).thenReturn(List.of(item));
        AiTaskWorker worker = new AiTaskWorker(taskRepository, itemRepository, sceneService);

        worker.run(new AiTaskCreatedEvent(700L, false));

        verify(sceneService).classify(101L, false);
        assertEquals(TaskStatus.SUCCESS, item.getStatus());
        assertEquals(TaskStatus.SUCCESS, task.getStatus());
        assertEquals(1, task.getSuccessCount());
        assertEquals(100, task.progressPercent());
    }
}
