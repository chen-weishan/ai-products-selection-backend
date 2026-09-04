package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductBatchQueueScoreResponse;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final AppUserRepository appUserRepository;
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository taskItemRepository;

    public ProductScoringBatchService(
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

        AiTask task = createTask(queuedProducts, null);

        return new ProductScoringBatchResult(
                task.getId(),
                queuedProducts.size(),
                activeIds.size()
        );
    }

    /**
     * 將使用者指定的品項盡量加入評分佇列。
     *
     * <p>與完整 AI 分析端點的整批驗證不同，本操作會略過不存在、不符合評分
     * 條件或已在 active task 的品項，讓同一批中其餘有效品項仍可成功排入。
     */
    public ProductBatchQueueScoreResponse enqueueByIds(
            Set<Long> productIds,
            String actorEmail
    ) {
        Set<Long> requestedIds = new LinkedHashSet<>(productIds);
        Map<Long, Product> productsById = productRepository.findAllById(requestedIds).stream()
                .collect(Collectors.toMap(
                        Product::getId,
                        product -> product,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        Set<Long> missingIds = new LinkedHashSet<>(requestedIds);
        missingIds.removeAll(productsById.keySet());

        Set<Long> ineligibleIds = requestedIds.stream()
                .filter(productsById::containsKey)
                .filter(id -> !isEligible(productsById.get(id)))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> eligibleIds = new LinkedHashSet<>(requestedIds);
        eligibleIds.removeAll(missingIds);
        eligibleIds.removeAll(ineligibleIds);

        Set<Long> activeIds = eligibleIds.isEmpty()
                ? Set.of()
                : new LinkedHashSet<>(taskItemRepository.findProductIdsInActiveTasks(
                        eligibleIds,
                        AiTaskType.FULL_ANALYSIS,
                        ACTIVE_STATUSES
                ));

        Set<Long> queuedIds = new LinkedHashSet<>(eligibleIds);
        queuedIds.removeAll(activeIds);
        List<Product> queuedProducts = queuedIds.stream()
                .map(productsById::get)
                .toList();

        AiTask task = null;
        if (!queuedProducts.isEmpty()) {
            AppUser actor = appUserRepository.findByEmail(actorEmail)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.UNAUTHORIZED,
                            "登入使用者不存在或已失效"
                    ));
            task = createTask(queuedProducts, actor);
        }

        List<String> warnings = new ArrayList<>();
        addWarning(warnings, missingIds, "找不到指定品項，已略過：");
        addWarning(warnings, ineligibleIds, "僅 A 軌且非草稿／已淘汰品項可評分，已略過：");
        addWarning(warnings, activeIds, "品項已在評分佇列中，已略過：");

        return new ProductBatchQueueScoreResponse(
                task == null ? null : task.getId(),
                task == null ? null : task.getStatus(),
                requestedIds.size(),
                queuedIds.size(),
                queuedIds,
                missingIds,
                ineligibleIds,
                activeIds,
                warnings
        );
    }

    private boolean isEligible(Product product) {
        return product.isScorable()
                && product.getStatus() != ProductStatus.DRAFT
                && product.getStatus() != ProductStatus.REJECTED;
    }

    private AiTask createTask(List<Product> products, AppUser actor) {
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
        return task;
    }

    private void addWarning(List<String> warnings, Set<Long> ids, String prefix) {
        if (!ids.isEmpty()) {
            warnings.add(prefix + ids);
        }
    }
}
