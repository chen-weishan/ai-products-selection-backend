package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.dto.CreateAiTaskRequest;
import com.example.ssds.api.exception.BusinessException;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class AiTaskServiceTest {
    @Mock AiTaskRepository taskRepository;
    @Mock AiTaskItemRepository itemRepository;
    @Mock ProductRepository productRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    void createsPendingTaskAndPublishesAfterCommitEvent() {
        Product product = Product.builder().id(101L).trackType(TrackType.A).build();
        when(productRepository.findAllById(List.of(101L))).thenReturn(List.of(product));
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(700L);
            return task;
        });
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        var response = service.create(new CreateAiTaskRequest(
                AiTaskType.SCENE_CLASSIFY,
                List.of(101L),
                new CreateAiTaskRequest.Options(false)));

        assertEquals(700L, response.taskId());
        assertEquals(TaskStatus.PENDING, response.status());
        assertEquals(1, response.totalCount());
        ArgumentCaptor<AiTaskCreatedEvent> event = ArgumentCaptor.forClass(AiTaskCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(700L, event.getValue().taskId());
        verify(itemRepository).saveAll(argThat(items -> items.iterator().hasNext()));
    }

    @Test
    void rejectsTrackBProductBeforeTaskCreation() {
        Product product = Product.builder().id(120L).trackType(TrackType.B).build();
        when(productRepository.findAllById(List.of(120L))).thenReturn(List.of(product));
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        assertThrows(BusinessException.class, () -> service.create(new CreateAiTaskRequest(
                AiTaskType.SCENE_CLASSIFY, List.of(120L), null)));

        verifyNoInteractions(taskRepository, itemRepository, eventPublisher);
    }

    @Test
    void acceptsReviewRiskTaskForTrackAProduct() {
        Product product = Product.builder().id(101L).trackType(TrackType.A).build();
        when(productRepository.findAllById(List.of(101L))).thenReturn(List.of(product));
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(701L);
            return task;
        });
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        var response = service.create(new CreateAiTaskRequest(
                AiTaskType.REVIEW_RISK, List.of(101L), null));

        assertEquals(AiTaskType.REVIEW_RISK, response.taskType());
        verify(eventPublisher).publishEvent(any(AiTaskCreatedEvent.class));
    }
}
