package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.dto.CreateAiTaskRequest;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.List;
import org.springframework.data.domain.Pageable;
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

    @Test
    void acceptsCombinedProductInsightTaskForTrackAProduct() {
        Product product = Product.builder().id(101L).trackType(TrackType.A).build();
        when(productRepository.findAllById(List.of(101L))).thenReturn(List.of(product));
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(702L);
            return task;
        });
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        var response = service.create(new CreateAiTaskRequest(
                AiTaskType.SELLING_POINT, List.of(101L), null));

        assertEquals(AiTaskType.SELLING_POINT, response.taskType());
        verify(eventPublisher).publishEvent(any(AiTaskCreatedEvent.class));
    }

    @Test
    void acceptsRecommendationTaskForTrackAProduct() {
        Product product = Product.builder().id(101L).trackType(TrackType.A).build();
        when(productRepository.findAllById(List.of(101L))).thenReturn(List.of(product));
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(703L);
            return task;
        });
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        var response = service.create(new CreateAiTaskRequest(
                AiTaskType.RECOMMENDATION, List.of(101L), null));

        assertEquals(AiTaskType.RECOMMENDATION, response.taskType());
        verify(eventPublisher).publishEvent(any(AiTaskCreatedEvent.class));
    }

    @Test
    void createsTrendInterpretTaskWithKeywordTargets() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("抹茶").build();
        when(keywordRepository.findAllById(List.of(31L))).thenReturn(List.of(keyword));
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(704L);
            return task;
        });
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository,
                keywordRepository, eventPublisher);

        var response = service.create(new CreateAiTaskRequest(
                AiTaskType.TREND_INTERPRET,
                List.of(),
                List.of(31L),
                new CreateAiTaskRequest.Options(true)));

        assertEquals(AiTaskType.TREND_INTERPRET, response.taskType());
        verify(itemRepository).saveAll(argThat(items -> {
            AiTaskItem item = items.iterator().next();
            return item.getProduct() == null && item.getKeyword().getId().equals(31L);
        }));
        verify(eventPublisher).publishEvent(any(AiTaskCreatedEvent.class));
    }

    @Test
    void reusesActiveSourcingTaskForSameProduct() {
        Product product = Product.builder().id(136L).trackType(TrackType.B).build();
        AiTask active = AiTask.builder()
                .id(24L).taskType(AiTaskType.SOURCING_SCOUT)
                .status(TaskStatus.RUNNING).totalCount(1).build();
        when(taskRepository.findActiveProductTasks(
                eq(136L), eq(AiTaskType.SOURCING_SCOUT), anyList(), any(Pageable.class)))
                .thenReturn(List.of(active));
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        var response = service.createSourcingScout(product, true);

        assertEquals(24L, response.taskId());
        assertEquals(TaskStatus.RUNNING, response.status());
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(itemRepository, eventPublisher);
    }

    @Test
    void createsSourcingTaskWhenNoActiveTaskExists() {
        Product product = Product.builder().id(136L).trackType(TrackType.B).build();
        when(taskRepository.findActiveProductTasks(
                eq(136L), eq(AiTaskType.SOURCING_SCOUT), anyList(), any(Pageable.class)))
                .thenReturn(List.of());
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(26L);
            return task;
        });
        AiTaskService service = new AiTaskService(
                taskRepository, itemRepository, productRepository, eventPublisher);

        var response = service.createSourcingScout(product, false);

        assertEquals(26L, response.taskId());
        verify(itemRepository).save(argThat(item -> item.getProduct().getId().equals(136L)));
        verify(eventPublisher).publishEvent(new AiTaskCreatedEvent(26L, false));
    }
}
