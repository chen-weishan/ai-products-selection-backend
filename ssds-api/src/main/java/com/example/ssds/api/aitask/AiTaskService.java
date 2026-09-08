package com.example.ssds.api.aitask;

import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskService {
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final TrendKeywordRepository keywordRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            ProductRepository productRepository,
            TrendKeywordRepository keywordRepository,
            ApplicationEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.keywordRepository = keywordRepository;
        this.eventPublisher = eventPublisher;
    }

    AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            ProductRepository productRepository,
            ApplicationEventPublisher eventPublisher) {
        this(taskRepository, itemRepository, productRepository, null, eventPublisher);
    }

    @Transactional
    public AiTaskResponse create(CreateAiTaskRequest request) {
        if (request.taskType() != AiTaskType.FULL_ANALYSIS
                && request.taskType() != AiTaskType.SCENE_CLASSIFY
                && request.taskType() != AiTaskType.REVIEW_RISK
                && request.taskType() != AiTaskType.SELLING_POINT
                && request.taskType() != AiTaskType.RECOMMENDATION
                && request.taskType() != AiTaskType.TREND_INTERPRET) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "目前只開放 FULL_ANALYSIS 與既有 Agent 測試任務");
        }
        if (request.taskType() == AiTaskType.TREND_INTERPRET) {
            return createKeywordTask(request);
        }
        if (request.taskType() == AiTaskType.FULL_ANALYSIS && request.productIds().isEmpty()) {
            return createFullAnalysis(productRepository.findFullAnalysisCandidates(eligibleStatuses()), request.forceRefresh());
        }
        if (!request.keywordIds().isEmpty() || request.productIds().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "品項型 AI 任務必須只提供 productIds");
        }
        List<Long> distinctIds = request.productIds().stream().distinct().toList();
        List<Product> products = productRepository.findAllById(distinctIds);
        if (products.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "部分品項不存在");
        }
        if (products.stream().anyMatch(product -> product.getTrackType() != TrackType.A)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    request.taskType() + " 任務只能包含 A 軌品項");
        }

        if (request.taskType() == AiTaskType.FULL_ANALYSIS
                && products.stream().anyMatch(product -> !eligibleStatuses().contains(product.getStatus()))) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "FULL_ANALYSIS 只接受 EVALUATING、WATCHING、ADOPTED 品項");
        }

        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(request.taskType())
                .budgetPool(request.taskType() == AiTaskType.FULL_ANALYSIS
                        ? AiTaskType.BudgetPool.TRACK_A : AiTaskType.BudgetPool.RETRY)
                .status(TaskStatus.PENDING)
                .totalCount(products.size())
                .build());
        List<AiTaskItem> items = products.stream()
                .map(product -> AiTaskItem.builder().task(task).product(product).build())
                .toList();
        itemRepository.saveAll(items);
        eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), request.forceRefresh()));
        return AiTaskResponse.from(task);
    }

    private AiTaskResponse createKeywordTask(CreateAiTaskRequest request) {
        if (!request.productIds().isEmpty() || request.keywordIds().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "趨勢解讀任務必須只提供 keywordIds");
        }
        List<Long> distinctIds = request.keywordIds().stream().distinct().toList();
        List<TrendKeyword> keywords = keywordRepository.findAllById(distinctIds);
        if (keywords.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "部分趨勢關鍵字不存在");
        }
        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(request.taskType())
                .budgetPool(AiTaskType.BudgetPool.RETRY)
                .status(TaskStatus.PENDING)
                .totalCount(keywords.size())
                .build());
        List<AiTaskItem> items = keywords.stream()
                .map(keyword -> AiTaskItem.builder().task(task).keyword(keyword).build())
                .toList();
        itemRepository.saveAll(items);
        eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), request.forceRefresh()));
        return AiTaskResponse.from(task);
    }

    /** Agent 6 專用入口；B 軌仍沿用相同的非同步 task/item 管線。 */
    @Transactional
    public AiTaskResponse createSourcingScout(Product product, boolean forceRefresh) {
        if (product.getTrackType() != TrackType.B) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "尋源探索只能使用 B 軌品項");
        }
        List<AiTask> active = taskRepository.findActiveProductTasks(
                product.getId(),
                AiTaskType.SOURCING_SCOUT,
                List.of(TaskStatus.PENDING, TaskStatus.RUNNING),
                PageRequest.of(0, 1));
        if (!active.isEmpty()) return AiTaskResponse.from(active.getFirst());
        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(AiTaskType.SOURCING_SCOUT).budgetPool(AiTaskType.BudgetPool.TRACK_B)
                .status(TaskStatus.PENDING).totalCount(1).build());
        itemRepository.save(AiTaskItem.builder().task(task).product(product).build());
        eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), forceRefresh));
        return AiTaskResponse.from(task);
    }

    /** 每週排程入口；空清單時不建立沒有工作項目的任務。 */
    @Transactional
    public Optional<AiTaskResponse> createScheduledFullAnalysis() {
        if (taskRepository.existsByTaskTypeAndStatusIn(
                AiTaskType.FULL_ANALYSIS, List.of(TaskStatus.PENDING, TaskStatus.RUNNING))) {
            return Optional.empty();
        }
        List<Product> products = productRepository.findFullAnalysisCandidates(eligibleStatuses());
        return products.isEmpty() ? Optional.empty() : Optional.of(createFullAnalysis(products, false));
    }

    /** 隔日續跑前一輪因配額或單輪上限略過的品項。 */
    @Transactional
    public Optional<AiTaskResponse> resumeQuotaSkippedFullAnalysis() {
        if (taskRepository.existsByTaskTypeAndStatusIn(
                AiTaskType.FULL_ANALYSIS, List.of(TaskStatus.PENDING, TaskStatus.RUNNING))) {
            return Optional.empty();
        }
        List<Product> products = itemRepository.findProductsPendingQuotaRetry(
                AiTaskType.FULL_ANALYSIS, com.example.ssds.core.domain.TaskItemStatus.SKIPPED_QUOTA);
        return products.isEmpty() ? Optional.empty() : Optional.of(createFullAnalysis(products, false));
    }

    private AiTaskResponse createFullAnalysis(List<Product> products, boolean forceRefresh) {
        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .status(TaskStatus.PENDING)
                .totalCount(products.size())
                .build());
        itemRepository.saveAll(products.stream()
                .map(product -> AiTaskItem.builder().task(task).product(product).build())
                .toList());
        eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), forceRefresh));
        return AiTaskResponse.from(task);
    }

    private static List<com.example.ssds.core.domain.ProductStatus> eligibleStatuses() {
        return List.of(
                com.example.ssds.core.domain.ProductStatus.EVALUATING,
                com.example.ssds.core.domain.ProductStatus.WATCHING,
                com.example.ssds.core.domain.ProductStatus.ADOPTED);
    }

    @Transactional(readOnly = true)
    public AiTaskResponse get(Long taskId) {
        return AiTaskResponse.from(taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的 AI 任務")));
    }

    @Transactional(readOnly = true)
    public List<AiTaskItemResponse> items(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的 AI 任務");
        }
        return itemRepository.findByTaskId(taskId).stream().map(AiTaskItemResponse::from).toList();
    }
}
