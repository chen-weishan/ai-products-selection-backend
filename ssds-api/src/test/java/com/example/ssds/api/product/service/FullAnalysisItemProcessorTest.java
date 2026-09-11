package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FullAnalysisItemProcessorTest {

    private AiTaskItemRepository itemRepository;
    private ProductFallbackScoringService scoringService;
    private FullAnalysisItemProcessor processor;

    @BeforeEach
    void setUp() {
        itemRepository = mock(AiTaskItemRepository.class);
        scoringService = mock(ProductFallbackScoringService.class);
        processor = new FullAnalysisItemProcessor(itemRepository, scoringService);
    }

    @Test
    void successfulScoreUpdatesLastScoringResult() {
        Product product = Product.builder().id(10L).trackType(TrackType.A).build();
        AiTaskItem item = pendingItem(product);
        when(itemRepository.findForProcessing(1L)).thenReturn(Optional.of(item));

        processor.process(1L);

        assertEquals(LastScoringStatus.SCORED, product.getLastScoringStatus());
        assertNotNull(product.getLastScoringAttemptedAt());
        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertNull(item.getErrorMessage());
    }

    @Test
    void insufficientDataIsCompletedWithoutRetryAndUpdatesProduct() {
        Product product = Product.builder().id(10L).trackType(TrackType.A).build();
        AiTaskItem item = pendingItem(product);
        when(itemRepository.findForProcessing(1L)).thenReturn(Optional.of(item));
        doThrow(new InsufficientDataException("資料不足"))
                .when(scoringService).score(product);

        processor.process(1L);

        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, product.getLastScoringStatus());
        assertNotNull(product.getLastScoringAttemptedAt());
        assertEquals(TaskItemStatus.SUCCEEDED, item.getStatus());
        assertNull(item.getErrorMessage());
    }

    @Test
    void technicalFailurePropagatesWithoutUpdatingLastScoringResult() {
        Product product = Product.builder().id(10L).trackType(TrackType.A).build();
        AiTaskItem item = pendingItem(product);
        when(itemRepository.findForProcessing(1L)).thenReturn(Optional.of(item));
        doThrow(new IllegalStateException("技術錯誤"))
                .when(scoringService).score(product);

        assertThrows(IllegalStateException.class, () -> processor.process(1L));

        assertNull(product.getLastScoringStatus());
        assertNull(product.getLastScoringAttemptedAt());
        assertEquals(TaskItemStatus.PENDING, item.getStatus());
    }

    private AiTaskItem pendingItem(Product product) {
        return AiTaskItem.builder()
                .id(1L)
                .product(product)
                .status(TaskItemStatus.PENDING)
                .build();
    }
}
