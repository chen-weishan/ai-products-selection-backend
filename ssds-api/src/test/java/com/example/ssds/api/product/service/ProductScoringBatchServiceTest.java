package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductScoringBatchServiceTest {

    private ProductRepository productRepository;
    private AiTaskRepository taskRepository;
    private AiTaskItemRepository taskItemRepository;
    private ProductScoringBatchService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        taskRepository = mock(AiTaskRepository.class);
        taskItemRepository = mock(AiTaskItemRepository.class);
        service = new ProductScoringBatchService(
                productRepository, taskRepository, taskItemRepository);
    }

    @Test
    void weeklyBatchQueuesEligibleProductsAndSkipsActiveOnes() {
        Product first = product(1L);
        Product second = product(2L);
        when(productRepository.findScorable(TrackType.A))
                .thenReturn(List.of(first, second));
        when(taskItemRepository.findProductIdsInActiveTasks(
                Set.of(1L, 2L),
                AiTaskType.FULL_ANALYSIS,
                Set.of(TaskStatus.PENDING, TaskStatus.RUNNING)))
                .thenReturn(Set.of(2L));
        when(taskRepository.saveAndFlush(any(AiTask.class)))
                .thenAnswer(invocation -> {
                    AiTask task = invocation.getArgument(0);
                    task.setId(88L);
                    return task;
                });

        ProductScoringBatchResult result = service.enqueueWeeklyBatch();

        assertEquals(88L, result.taskId());
        assertEquals(1, result.queuedCount());
        assertEquals(1, result.skippedActiveCount());
        verify(taskItemRepository).saveAllAndFlush(anyList());
    }

    @Test
    void weeklyBatchDoesNotCreateEmptyTask() {
        when(productRepository.findScorable(TrackType.A)).thenReturn(List.of());

        ProductScoringBatchResult result = service.enqueueWeeklyBatch();

        assertNull(result.taskId());
        assertEquals(0, result.queuedCount());
        verify(taskRepository, never()).saveAndFlush(any());
    }

    private Product product(Long id) {
        return Product.builder()
                .id(id)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .build();
    }
}
