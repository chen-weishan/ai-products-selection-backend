package com.example.ssds.api.aitask.service;

import com.example.ssds.api.aitask.execution.AiTaskCreatedEvent;
import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskService {
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");
    private static final Set<TaskStatus> ACTIVE_TASK_STATUSES =
            Set.of(TaskStatus.PENDING, TaskStatus.RUNNING);
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
            return enqueueFullAnalysis(
                    productRepository.findFullAnalysisCandidates(eligibleStatuses()),
                    null,
                    request.forceRefresh());
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

        if (request.taskType() == AiTaskType.FULL_ANALYSIS) {
            return enqueueFullAnalysis(products, null, request.forceRefresh());
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
        List<Long> distinctIds = keywordIds.stream().distinct().toList();
        Set<Long> activeKeywordIds = itemRepository.findKeywordIdsInActiveTasks(
                new LinkedHashSet<>(distinctIds),
                AiTaskType.TREND_INTERPRET,
                Set.of(TaskStatus.PENDING, TaskStatus.RUNNING));
        List<Long> pendingIds = distinctIds.stream()
                .filter(keywordId -> !activeKeywordIds.contains(keywordId))
                .toList();
        if (pendingIds.isEmpty()) return Optional.empty();
        CreateAiTaskRequest request = new CreateAiTaskRequest(
                AiTaskType.TREND_INTERPRET,
                List.of(),
                pendingIds,
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
        return products.isEmpty()
                ? Optional.empty()
                : Optional.of(createFullAnalysis(products, null, false));
    }

    /**
     * 星期二至星期日補跑本週尚未完成的合格品項。
     *
     * <p>範圍包含前一輪 SKIPPED_QUOTA 及本週新增的合格品項；任何排程或人工
     * FULL_ANALYSIS 已於本週成功完成的品項都會由 repository 排除。
     */
    @Transactional
    public Optional<AiTaskResponse> createFullAnalysisCatchUp() {
        if (taskRepository.existsByTaskTypeAndStatusIn(
                AiTaskType.FULL_ANALYSIS, List.of(TaskStatus.PENDING, TaskStatus.RUNNING))) {
            return Optional.empty();
        }
        Instant weekStart = LocalDate.now(API_ZONE)
                .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .atStartOfDay(API_ZONE)
                .toInstant();
        List<Product> products = productRepository.findFullAnalysisCatchUpCandidates(
                eligibleStatuses(), weekStart);
        return products.isEmpty()
                ? Optional.empty()
                : Optional.of(createFullAnalysis(products, null, false));
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

    /**
     * FR-03 與 FR-07 共用的 FULL_ANALYSIS 建立入口。
     *
     * <p>同品項已有 PENDING/RUNNING 任務時不建立平行任務，而是回傳既有任務。
     * 評分輸入的修改由 FR-03 寫入服務在進入本方法前阻擋；任務完成或取消後，
     * 使用者再次儲存才會建立使用最新資料的新任務。
     */
    @Transactional
    public AiTaskResponse enqueueFullAnalysis(
            List<Product> products,
            AppUser createdBy,
            boolean forceRefresh) {
        if (products == null || products.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "FULL_ANALYSIS 至少需要一個品項");
        }
        List<Long> productIds = products.stream()
                .map(Product::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (productIds.size() != products.stream().map(Product::getId).distinct().count()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "FULL_ANALYSIS 品項必須已完成儲存");
        }

        List<Product> lockedProducts = productIds.stream()
                .map(productId -> productRepository.findByIdForUpdate(productId)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.RESOURCE_NOT_FOUND,
                                "找不到指定的品項：" + productId)))
                .toList();
        Set<Long> activeProductIds = itemRepository.findProductIdsInActiveTasks(
                new LinkedHashSet<>(productIds),
                AiTaskType.FULL_ANALYSIS,
                ACTIVE_TASK_STATUSES);

        List<Product> queuedProducts = new ArrayList<>();
        for (Product product : lockedProducts) {
            if (!activeProductIds.contains(product.getId())) {
                Optional<AiTask> cancellationInProgress = taskRepository.findBlockingProductTasks(
                                product.getId(),
                                AiTaskType.FULL_ANALYSIS,
                                new ArrayList<>(ACTIVE_TASK_STATUSES),
                                TaskStatus.CANCELLED,
                                PageRequest.of(0, 1))
                        .stream()
                        .filter(task -> task.getStatus() == TaskStatus.CANCELLED
                                && task.getFinishedAt() == null)
                        .findFirst();
                if (cancellationInProgress.isPresent()) {
                    throw new BusinessException(
                            ErrorCode.PRODUCT_ANALYSIS_IN_PROGRESS,
                            "品項評分任務 #" + cancellationInProgress.get().getId()
                                    + " 正在取消，請等待取消完成後再重新執行");
                }
                queuedProducts.add(product);
            }
        }

        if (!queuedProducts.isEmpty()) {
            return createFullAnalysis(queuedProducts, createdBy, forceRefresh);
        }

        for (Long productId : productIds) {
            Optional<AiTask> activeTask = taskRepository.findActiveProductTasks(
                            productId,
                            AiTaskType.FULL_ANALYSIS,
                            new ArrayList<>(ACTIVE_TASK_STATUSES),
                            PageRequest.of(0, 1))
                    .stream()
                    .findFirst();
            if (activeTask.isPresent()) {
                return AiTaskResponse.from(activeTask.get());
            }
        }

        // Worker 可能恰好在「查出活動品項」後完成任務。此時直接建立替代任務，
        // 避免把正常的完成競態回成 500；品項鎖可防止另一個請求同時建立。
        return createFullAnalysis(lockedProducts, createdBy, forceRefresh);
    }

    /**
     * FR-03 評分輸入寫入防線：活動任務期間禁止改變本次分析的輸入快照。
     * 成功、失敗、部分完成會解除限制；取消中的 RUNNING 任務則要等 worker
     * 確認停止並寫入 finishedAt 後才解除，避免背景結果覆蓋使用者的新資料。
     */
    @Transactional(readOnly = true)
    public void assertFullAnalysisInputsEditable(Long productId) {
        taskRepository.findBlockingProductTasks(
                        productId,
                        AiTaskType.FULL_ANALYSIS,
                        new ArrayList<>(ACTIVE_TASK_STATUSES),
                        TaskStatus.CANCELLED,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .ifPresent(task -> {
                    throw new BusinessException(
                            ErrorCode.PRODUCT_ANALYSIS_IN_PROGRESS,
                            "品項正在評分（任務 #" + task.getId()
                                    + "，狀態 " + task.getStatus()
                                    + "），請等待任務完成，或等待取消作業完成後再修改評分資料");
                });
    }

    private AiTaskResponse createFullAnalysis(
            List<Product> products,
            AppUser createdBy,
            boolean forceRefresh) {
        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .status(TaskStatus.PENDING)
                .totalCount(products.size())
                .createdBy(createdBy)
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
    public PageResponse<AiTaskResponse> list(TaskStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return PageResponse.from((status == null
                ? taskRepository.findAllByOrderByIdDesc(pageable)
                : taskRepository.findByStatusOrderByIdDesc(status, pageable))
                .map(AiTaskResponse::from));
    }

    @Transactional(readOnly = true)
    public AiTaskSummaryResponse summary() {
        Instant monthStart = LocalDate.now(API_ZONE)
                .withDayOfMonth(1)
                .atStartOfDay(API_ZONE)
                .toInstant();
        return new AiTaskSummaryResponse(
                taskRepository.countByStatus(TaskStatus.RUNNING),
                taskRepository.countByStatusAndFinishedAtGreaterThanEqual(TaskStatus.SUCCEEDED, monthStart),
                taskRepository.countByStatusIn(List.of(TaskStatus.FAILED, TaskStatus.PARTIAL)));
    }

    @Transactional
    public AiTaskResponse cancel(Long taskId) {
        AiTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的 AI 任務"));
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.RUNNING) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "只有排隊中或執行中的 AI 任務可以取消");
        }
        boolean running = task.getStatus() == TaskStatus.RUNNING;
        task.setStatus(TaskStatus.CANCELLED);
        // RUNNING 代表取消要求已送出，但背景工作可能仍在當前分析步驟中。
        // finishedAt 保持 null，直到 worker 確認停止；在此之前品項仍維持編輯鎖。
        task.setFinishedAt(running ? null : Instant.now());
        return AiTaskResponse.from(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public List<AiTaskItemResponse> items(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的 AI 任務");
        }
        return itemRepository.findByTaskId(taskId).stream().map(AiTaskItemResponse::from).toList();
    }
}
