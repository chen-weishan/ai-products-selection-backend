package com.example.ssds.api.product.service;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** §5.10 每週全量評分：建立 FULL_ANALYSIS 任務，交由 FR-07 執行器消費。 */
@Service
@Transactional
public class ProductScoringBatchService {

    private static final Set<TaskStatus> ACTIVE_STATUSES =
            Set.of(TaskStatus.PENDING, TaskStatus.RUNNING);

    private final ProductRepository productRepository;
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository taskItemRepository;

    public ProductScoringBatchService(
            ProductRepository productRepository,
            AiTaskRepository taskRepository,
            AiTaskItemRepository taskItemRepository
    ) {
        this.productRepository = productRepository;
        this.taskRepository = taskRepository;
        this.taskItemRepository = taskItemRepository;
    }

    public ProductScoringBatchResult enqueueWeeklyBatch() {
        List<Product> scorableProducts = productRepository.findScorable(TrackType.A);
        Set<Long> productIds = scorableProducts.stream()
                .map(Product::getId)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return new ProductScoringBatchResult(null, 0, 0);
        }

        Set<Long> activeIds = taskItemRepository.findProductIdsInActiveTasks(
                productIds,
                AiTaskType.FULL_ANALYSIS,
                ACTIVE_STATUSES
        );
        List<Product> queuedProducts = scorableProducts.stream()
                .filter(product -> !activeIds.contains(product.getId()))
                .toList();
        if (queuedProducts.isEmpty()) {
            return new ProductScoringBatchResult(null, 0, activeIds.size());
        }

        AiTask task = taskRepository.saveAndFlush(AiTask.builder()
                .taskType(AiTaskType.FULL_ANALYSIS)
                .status(TaskStatus.PENDING)
                .totalCount(queuedProducts.size())
                .createdBy(null)
                .build());
        taskItemRepository.saveAllAndFlush(queuedProducts.stream()
                .map(product -> AiTaskItem.builder()
                        .task(task)
                        .product(product)
                        .status(TaskItemStatus.PENDING)
                        .build())
                .toList());

        return new ProductScoringBatchResult(
                task.getId(),
                queuedProducts.size(),
                activeIds.size()
        );
    }
}
