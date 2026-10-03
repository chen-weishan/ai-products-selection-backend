package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductScoringBatchServiceTest {

    private ProductRepository productRepository;
    private AppUserRepository appUserRepository;
    private AiTaskItemRepository taskItemRepository;
    private AiTaskService taskService;
    private ProductScoringBatchService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        taskItemRepository = mock(AiTaskItemRepository.class);
        taskService = mock(AiTaskService.class);
        service = new ProductScoringBatchService(
                productRepository, appUserRepository, taskItemRepository, taskService);
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
        AiTaskResponse task = mock(AiTaskResponse.class);
        when(task.taskId()).thenReturn(88L);
        when(taskService.enqueueFullAnalysis(anyList(), any(), any(Boolean.class)))
                .thenReturn(task);

        ProductScoringBatchResult result = service.enqueueWeeklyBatch();

        assertEquals(88L, result.taskId());
        assertEquals(1, result.queuedCount());
        assertEquals(1, result.skippedActiveCount());
        verify(taskService).enqueueFullAnalysis(anyList(), any(), any(Boolean.class));
    }

    @Test
    void weeklyBatchDoesNotCreateEmptyTask() {
        when(productRepository.findScorable(TrackType.A)).thenReturn(List.of());

        ProductScoringBatchResult result = service.enqueueWeeklyBatch();

        assertNull(result.taskId());
        assertEquals(0, result.queuedCount());
        verify(taskService, never()).enqueueFullAnalysis(anyList(), any(), any(Boolean.class));
    }

    @Test
    void manualBatchQueuesEligibleProductsAndReportsEverySkippedReason() {
        Product eligible = product(1L);
        Product active = product(2L);
        Product trackB = Product.builder()
                .id(3L)
                .trackType(TrackType.B)
                .status(ProductStatus.EVALUATING)
                .build();
        Product draft = Product.builder()
                .id(4L)
                .trackType(TrackType.A)
                .status(ProductStatus.DRAFT)
                .build();
        AppUser actor = AppUser.builder().id(9L).email("buyer@ssds.dev").build();
        Set<Long> requestedIds = Set.of(1L, 2L, 3L, 4L, 99L);

        when(productRepository.findAllById(requestedIds))
                .thenReturn(List.of(eligible, active, trackB, draft));
        when(taskItemRepository.findProductIdsInActiveTasks(
                Set.of(1L, 2L),
                AiTaskType.FULL_ANALYSIS,
                Set.of(TaskStatus.PENDING, TaskStatus.RUNNING)))
                .thenReturn(Set.of(2L));
        when(appUserRepository.findByEmail("buyer@ssds.dev"))
                .thenReturn(java.util.Optional.of(actor));
        AiTaskResponse task = mock(AiTaskResponse.class);
        when(task.taskId()).thenReturn(89L);
        when(task.status()).thenReturn(TaskStatus.PENDING);
        when(taskService.enqueueFullAnalysis(anyList(), any(AppUser.class), any(Boolean.class)))
                .thenReturn(task);

        var result = service.enqueueByIds(requestedIds, "buyer@ssds.dev");

        assertEquals(89L, result.taskId());
        assertEquals(TaskStatus.PENDING, result.status());
        assertEquals(5, result.requestedCount());
        assertEquals(1, result.queuedCount());
        assertEquals(Set.of(1L), result.queuedProductIds());
        assertEquals(Set.of(99L), result.missingProductIds());
        assertEquals(Set.of(3L, 4L), result.ineligibleProductIds());
        assertEquals(Set.of(2L), result.alreadyQueuedProductIds());
        assertEquals(3, result.warnings().size());
        verify(taskService).enqueueFullAnalysis(anyList(), org.mockito.ArgumentMatchers.argThat(taskActor -> {
            assertSame(actor, taskActor);
            return true;
        }), any(Boolean.class));
    }

    @Test
    void manualBatchDoesNotCreateTaskWhenEveryProductIsSkipped() {
        Product trackB = Product.builder()
                .id(3L)
                .trackType(TrackType.B)
                .status(ProductStatus.EVALUATING)
                .build();
        Set<Long> requestedIds = Set.of(3L, 99L);
        when(productRepository.findAllById(requestedIds)).thenReturn(List.of(trackB));

        var result = service.enqueueByIds(requestedIds, "buyer@ssds.dev");

        assertNull(result.taskId());
        assertNull(result.status());
        assertEquals(0, result.queuedCount());
        assertEquals(Set.of(99L), result.missingProductIds());
        assertEquals(Set.of(3L), result.ineligibleProductIds());
        verify(taskService, never()).enqueueFullAnalysis(anyList(), any(), any(Boolean.class));
        verify(appUserRepository, never()).findByEmail(any());
    }

    private Product product(Long id) {
        return Product.builder()
                .id(id)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .build();
    }
}
