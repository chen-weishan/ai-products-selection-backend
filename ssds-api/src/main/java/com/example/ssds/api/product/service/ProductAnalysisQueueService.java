package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.product.dto.ProductBatchAnalyzeRequest;
import com.example.ssds.api.product.dto.ProductBatchAnalyzeResponse;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 建立 FR-03 批次完整分析任務；實際背景執行由 FR-07 消費。 */
@Service
@Transactional
public class ProductAnalysisQueueService {

    private static final Set<TaskStatus> ACTIVE_STATUSES =
            Set.of(TaskStatus.PENDING, TaskStatus.RUNNING);

    private final ProductRepository productRepository;
    private final AppUserRepository appUserRepository;
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository taskItemRepository;

    public ProductAnalysisQueueService(
            ProductRepository productRepository,
            AppUserRepository appUserRepository,
            AiTaskRepository taskRepository,
            AiTaskItemRepository taskItemRepository
    ) {
        this.productRepository = productRepository;
        this.appUserRepository = appUserRepository;
        this.taskRepository = taskRepository;
        this.taskItemRepository = taskItemRepository;
    }

    public ProductBatchAnalyzeResponse enqueue(
            ProductBatchAnalyzeRequest request,
            String actorEmail
    ) {
        Set<Long> requestedIds = new LinkedHashSet<>(request.productIds());
        List<Product> products = productRepository.findAllById(requestedIds);
        Set<Long> missingIds = new LinkedHashSet<>(requestedIds);
        products.forEach(product -> missingIds.remove(product.getId()));
        if (!missingIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "找不到指定的品項：" + missingIds
            );
        }

        Set<Long> invalidIds = products.stream()
                .filter(product -> product.getTrackType() != TrackType.A
                        || product.getStatus() == ProductStatus.DRAFT
                        || product.getStatus() == ProductStatus.REJECTED)
                .map(Product::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!invalidIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "批次分析驗證失敗",
                    List.of(new FieldError(
                            "productIds",
                            "僅 A 軌且非草稿／已淘汰品項可加入分析佇列：" + invalidIds
                    ))
            );
        }

        Set<Long> activeIds = taskItemRepository.findProductIdsInActiveTasks(
                requestedIds,
                AiTaskType.FULL_ANALYSIS,
                ACTIVE_STATUSES
        );
        if (!activeIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.DUPLICATE_RESOURCE,
                    "品項已在分析佇列中：" + activeIds
            );
        }

        AppUser actor = appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED,
                        "登入使用者不存在或已失效"
                ));
        AiTask task = taskRepository.saveAndFlush(AiTask.builder()
                .taskType(AiTaskType.FULL_ANALYSIS)
                .status(TaskStatus.PENDING)
                .totalCount(products.size())
                .createdBy(actor)
                .build());
        taskItemRepository.saveAllAndFlush(products.stream()
                .map(product -> AiTaskItem.builder()
                        .task(task)
                        .product(product)
                        .status(TaskItemStatus.PENDING)
                        .build())
                .toList());

        return new ProductBatchAnalyzeResponse(
                task.getId(),
                task.getTaskType(),
                task.getStatus(),
                products.size(),
                requestedIds
        );
    }
}
