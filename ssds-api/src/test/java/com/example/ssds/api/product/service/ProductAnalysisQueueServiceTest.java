package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductBatchAnalyzeRequest;
import com.example.ssds.api.product.dto.ProductBatchAnalyzeResponse;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductAnalysisQueueServiceTest {

    private ProductRepository productRepository;
    private AppUserRepository appUserRepository;
    private AiTaskRepository taskRepository;
    private AiTaskItemRepository taskItemRepository;
    private ProductAnalysisQueueService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        taskRepository = mock(AiTaskRepository.class);
        taskItemRepository = mock(AiTaskItemRepository.class);
        service = new ProductAnalysisQueueService(
                productRepository,
                appUserRepository,
                taskRepository,
                taskItemRepository
        );
    }

    @Test
    void enqueueCreatesOneTaskAndItemsForAllProducts() {
        Product first = product(10L, TrackType.A, ProductStatus.EVALUATING);
        Product second = product(11L, TrackType.A, ProductStatus.WATCHING);
        AppUser actor = AppUser.builder().id(5L).email("buyer@ssds.dev").build();
        when(productRepository.findAllById(Set.of(10L, 11L)))
                .thenReturn(List.of(first, second));
        when(taskItemRepository.findProductIdsInActiveTasks(
                any(), any(), any())).thenReturn(Set.of());
        when(appUserRepository.findByEmail(actor.getEmail()))
                .thenReturn(Optional.of(actor));
        when(taskRepository.saveAndFlush(any(AiTask.class)))
                .thenAnswer(invocation -> {
                    AiTask task = invocation.getArgument(0);
                    task.setId(90L);
                    return task;
                });

        ProductBatchAnalyzeResponse response = service.enqueue(
                new ProductBatchAnalyzeRequest(Set.of(10L, 11L)),
                actor.getEmail()
        );

        assertEquals(90L, response.taskId());
        assertEquals(AiTaskType.FULL_ANALYSIS, response.taskType());
        assertEquals(TaskStatus.PENDING, response.status());
        assertEquals(2, response.queuedCount());
        verify(taskItemRepository).saveAllAndFlush(anyList());
    }

    @Test
    void enqueueRejectsTrackBAtomically() {
        Product trackB = product(10L, TrackType.B, ProductStatus.EVALUATING);
        when(productRepository.findAllById(Set.of(10L)))
                .thenReturn(List.of(trackB));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enqueue(
                        new ProductBatchAnalyzeRequest(Set.of(10L)),
                        "buyer@ssds.dev"
                )
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void enqueueRejectsProductAlreadyInActiveTask() {
        Product product = product(10L, TrackType.A, ProductStatus.EVALUATING);
        when(productRepository.findAllById(Set.of(10L)))
                .thenReturn(List.of(product));
        when(taskItemRepository.findProductIdsInActiveTasks(
                any(), any(), any())).thenReturn(Set.of(10L));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enqueue(
                        new ProductBatchAnalyzeRequest(Set.of(10L)),
                        "buyer@ssds.dev"
                )
        );

        assertEquals(ErrorCode.DUPLICATE_RESOURCE, exception.getErrorCode());
        verify(taskRepository, never()).saveAndFlush(any());
    }

    private Product product(Long id, TrackType trackType, ProductStatus status) {
        return Product.builder()
                .id(id)
                .trackType(trackType)
                .status(status)
                .build();
    }
}
