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
    private final CalibrationReportRepository calibrationReportRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            ProductRepository productRepository,
            TrendKeywordRepository keywordRepository,
            CalibrationReportRepository calibrationReportRepository,
            ApplicationEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.keywordRepository = keywordRepository;
        this.calibrationReportRepository = calibrationReportRepository;
        this.eventPublisher = eventPublisher;
    }

    AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            ProductRepository productRepository,
            ApplicationEventPublisher eventPublisher) {
        this(taskRepository, itemRepository, productRepository, null, null, eventPublisher);
    }

    AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            ProductRepository productRepository,
            TrendKeywordRepository keywordRepository,
            ApplicationEventPublisher eventPublisher) {
        this(taskRepository, itemRepository, productRepository, keywordRepository, null, eventPublisher);
    }

    @Transactional
    public AiTaskResponse create(CreateAiTaskRequest request) {
        if (request.taskType() != AiTaskType.FULL_ANALYSIS
                && request.taskType() != AiTaskType.SCENE_CLASSIFY
                && request.taskType() != AiTaskType.REVIEW_RISK
                && request.taskType() != AiTaskType.SELLING_POINT
                && request.taskType() != AiTaskType.RECOMMENDATION
                && request.taskType() != AiTaskType.TREND_INTERPRET
                && request.taskType() != AiTaskType.WEIGHT_CALIBRATION) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "目前只開放 FULL_ANALYSIS 與 Agent 1～7 測試任務");
        }
        if (request.taskType() == AiTaskType.TREND_INTERPRET) {
            return createKeywordTask(request, AiTaskType.BudgetPool.RETRY);
        }
        if (request.taskType() == AiTaskType.WEIGHT_CALIBRATION) {
            return createCalibrationTask(request);
        }
        if (request.taskType() == AiTaskType.FULL_ANALYSIS && request.productIds().isEmpty()) {
            return createFullAnalysis(productRepository.findFullAnalysisCandidates(eligibleStatuses()), request.forceRefresh());
        }
        if (!request.keywordIds().isEmpty()
                || !request.calibrationReportIds().isEmpty()
                || request.productIds().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "品項型 AI 任務必須只提供 productIds");
        }
        List<Long> distinctIds = request.productIds().stream().distinct().toList();
        List<Product> products = productRepository.findAllById(distinctIds);
        if (products.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "部分品項不存在");
        }
        if (products.stream().anyMatch(product -> product.getDeletedAt() != null)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "部分品項不存在或已刪除");
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

    private AiTaskResponse createKeywordTask(
            CreateAiTaskRequest request, AiTaskType.BudgetPool budgetPool) {
        if (!request.productIds().isEmpty()
                || !request.calibrationReportIds().isEmpty()
                || request.keywordIds().isEmpty()) {
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
                .budgetPool(budgetPool)
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

    private AiTaskResponse createCalibrationTask(CreateAiTaskRequest request) {
        if (!request.productIds().isEmpty()
                || !request.keywordIds().isEmpty()
                || request.calibrationReportIds().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "權重校準任務必須只提供 calibrationReportIds");
        }
        if (calibrationReportRepository == null) {
            throw new IllegalStateException("CalibrationReportRepository 尚未設定");
        }
        List<Long> distinctIds = request.calibrationReportIds().stream().distinct().toList();
        List<CalibrationReport> reports = calibrationReportRepository.findAllById(distinctIds);
        if (reports.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "部分校準報告不存在");
        }
        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(AiTaskType.WEIGHT_CALIBRATION)
                .budgetPool(AiTaskType.BudgetPool.RETRY)
                .status(TaskStatus.PENDING)
                .totalCount(reports.size())
                .build());
        itemRepository.saveAll(reports.stream()
                .map(report -> AiTaskItem.builder()
                        .task(task)
                        .calibrationReport(report)
                        .build())
                .toList());
        eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), request.forceRefresh()));
        return AiTaskResponse.from(task);
    }

    /** 每日排程使用 TRACK_A；手動建立的 TREND_INTERPRET 任務仍走 RETRY。 */
    @Transactional
    public Optional<AiTaskResponse> createScheduledTrendInterpretation(List<Long> keywordIds) {
        if (keywordIds == null || keywordIds.isEmpty()) return Optional.empty();
        CreateAiTaskRequest request = new CreateAiTaskRequest(
                AiTaskType.TREND_INTERPRET,
                List.of(),
                keywordIds,
                List.of(),
                new CreateAiTaskRequest.Options(false));
        return Optional.of(createKeywordTask(request, AiTaskType.BudgetPool.TRACK_A));
    }

    /** Agent 6 專用入口；B 軌仍沿用相同的非同步 task/item 管線。 */
    @Transactional
    public AiTaskResponse createSourcingScout(Product product, boolean forceRefresh) {
        if (product.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "品項不存在或已刪除");
        }
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

    /** FR-07：人工重跑指定任務內的一般失敗項；原 task/item 保留作稽核。 */
    @Transactional
    public AiTaskResponse retryFailedItems(Long sourceTaskId) {
        AiTask source = taskRepository.findById(sourceTaskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的 AI 任務"));
        List<AiTaskItem> failed = itemRepository.findByTaskIdAndStatus(
                sourceTaskId, com.example.ssds.core.domain.TaskItemStatus.FAILED);
        List<AiTaskItem> retryable = failed.stream()
                .filter(item -> item.getProduct() == null || item.getProduct().getDeletedAt() == null)
                .toList();
        if (retryable.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "此任務沒有可重跑的失敗項");
        }
        AiTask retryTask = taskRepository.save(AiTask.builder()
                .taskType(source.getTaskType())
                .budgetPool(AiTaskType.BudgetPool.RETRY)
                .status(TaskStatus.PENDING)
                .totalCount(retryable.size())
                .build());
        itemRepository.saveAll(retryable.stream()
                .map(item -> AiTaskItem.builder()
                        .task(retryTask)
                        .product(item.getProduct())
                        .keyword(item.getKeyword())
                        .calibrationReport(item.getCalibrationReport())
                        .build())
                .toList());
        eventPublisher.publishEvent(new AiTaskCreatedEvent(retryTask.getId(), true));
        return AiTaskResponse.from(retryTask);
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
