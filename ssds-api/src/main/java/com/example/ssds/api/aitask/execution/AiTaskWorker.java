package com.example.ssds.api.aitask.execution;

import com.example.ssds.api.aitask.fullanalysis.FullAnalysisOrchestrator;
import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.api.calibration.WeightCalibrationService;
import com.example.ssds.api.scoring.PureScoringBatchService;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import com.example.ssds.ai.access.common.AiExecutionWarningContext;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.budget.AiBudgetExecutionContext;
import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AiTaskWorker {
    private static final Logger log = LoggerFactory.getLogger(AiTaskWorker.class);
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository itemRepository;
    private final SceneClassificationService sceneClassificationService;
    private final ReviewRiskService reviewRiskService;
    private final ProductInsightService productInsightService;
    private final RecommendationService recommendationService;
    private final TrendInterpretationService trendInterpretationService;
    private final SourcingScoutService sourcingScoutService;
    private final WeightCalibrationService weightCalibrationService;
    private final FullAnalysisOrchestrator fullAnalysisOrchestrator;
    private final DailyAiBudget dailyAiBudget;
    private final int batchItemCap;
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();
    private PureScoringBatchService pureScoringBatchService;

    @Autowired
    void setPureScoringBatchService(PureScoringBatchService pureScoringBatchService) {
        this.pureScoringBatchService = pureScoringBatchService;
    }

    @Autowired
    public AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService,
            TrendInterpretationService trendInterpretationService,
            SourcingScoutService sourcingScoutService,
            WeightCalibrationService weightCalibrationService,
            FullAnalysisOrchestrator fullAnalysisOrchestrator,
            DailyAiBudget dailyAiBudget,
            @Value("${ai.batch-item-cap:150}") int batchItemCap) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.sceneClassificationService = sceneClassificationService;
        this.reviewRiskService = reviewRiskService;
        this.productInsightService = productInsightService;
        this.recommendationService = recommendationService;
        this.trendInterpretationService = trendInterpretationService;
        this.sourcingScoutService = sourcingScoutService;
        this.weightCalibrationService = weightCalibrationService;
        this.fullAnalysisOrchestrator = fullAnalysisOrchestrator;
        this.dailyAiBudget = dailyAiBudget;
        this.batchItemCap = Math.max(0, batchItemCap);
    }

    AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService) {
        this(taskRepository, itemRepository, sceneClassificationService, reviewRiskService,
                productInsightService, recommendationService, null, null, null, null, null, 150);
    }

    AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService,
            TrendInterpretationService trendInterpretationService) {
        this(taskRepository, itemRepository, sceneClassificationService, reviewRiskService,
                productInsightService, recommendationService, trendInterpretationService, null, null, null, null, 150);
    }

    AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService,
            TrendInterpretationService trendInterpretationService,
            SourcingScoutService sourcingScoutService) {
        this(taskRepository, itemRepository, sceneClassificationService, reviewRiskService,
                productInsightService, recommendationService, trendInterpretationService,
                sourcingScoutService, null, null, null, 150);
    }

    AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService,
            TrendInterpretationService trendInterpretationService,
            SourcingScoutService sourcingScoutService,
            WeightCalibrationService weightCalibrationService) {
        this(taskRepository, itemRepository, sceneClassificationService, reviewRiskService,
                productInsightService, recommendationService, trendInterpretationService,
                sourcingScoutService, weightCalibrationService, null, null, 150);
    }

    AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService,
            TrendInterpretationService trendInterpretationService,
            SourcingScoutService sourcingScoutService,
            FullAnalysisOrchestrator fullAnalysisOrchestrator,
            DailyAiBudget dailyAiBudget,
            int batchItemCap) {
        this(taskRepository, itemRepository, sceneClassificationService, reviewRiskService,
                productInsightService, recommendationService, trendInterpretationService,
                sourcingScoutService, null, fullAnalysisOrchestrator, dailyAiBudget, batchItemCap);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void run(AiTaskCreatedEvent event) {
        if (!activeTaskIds.add(event.taskId())) {
            log.debug("AI task is already active; duplicate dispatch skipped: taskId={}", event.taskId());
            return;
        }
        try {
            execute(event);
        } catch (RuntimeException exception) {
            log.error("AI task worker interrupted unexpectedly: taskId={}", event.taskId(), exception);
            settleInterruptedTask(event.taskId(), exception);
        } finally {
            activeTaskIds.remove(event.taskId());
        }
    }

    public boolean isTaskActive(Long taskId) {
        return activeTaskIds.contains(taskId);
    }

    private void execute(AiTaskCreatedEvent event) {
        AiTask task = taskRepository.findById(event.taskId()).orElseThrow();
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.RUNNING) {
            log.debug("AI task already finished; stale dispatch skipped: taskId={}, status={}",
                    task.getId(), task.getStatus());
            return;
        }
        task.setStatus(TaskStatus.RUNNING);
        if (task.getStartedAt() == null) task.setStartedAt(Instant.now());
        task.setFinishedAt(null);
        taskRepository.save(task);

        List<AiTaskItem> allItems = itemRepository.findByTaskId(task.getId());
        List<AiTaskItem> items = allItems.stream()
                .filter(item -> item.getStatus() == TaskItemStatus.PENDING)
                .toList();
        PureScoringPreparation pureScoring = preparePureScores(task, items);

        int successes = successCount(allItems);
        int failures = failureCount(allItems);
        int analyzedItems = successes + failures;
        boolean quotaExhausted = false;
        for (AiTaskItem item : items) {
            if (wasCancelled(task)) return;
            Instant started = Instant.now();
            AiExecutionWarningContext.clear();
            AiBudgetExecutionContext.begin(task.getBudgetPool());
            try {
                String pureScoringFailure = item.getProduct() == null
                        ? null
                        : pureScoring.failures().get(item.getProduct().getId());
                if (pureScoringFailure != null) {
                    throw new PureScoringFailedException(pureScoringFailure);
                }
                if (task.getTaskType() == AiTaskType.FULL_ANALYSIS
                        && (quotaExhausted || analyzedItems >= batchItemCap)) {
                    throw new DeferredItemException(quotaExhausted
                            ? "TRACK_A 今日配額已耗盡，已排入隔日續跑"
                            : "已達單輪 AI 分析品項上限，已排入隔日續跑");
                }
                String warning = null;
                switch (task.getTaskType()) {
                    case FULL_ANALYSIS -> {
                        FullAnalysisOrchestrator.Result result = pureScoring.factorPopulation() == null
                                ? fullAnalysisOrchestrator.analyze(
                                        item.getProduct().getId(), event.forceRefresh())
                                : fullAnalysisOrchestrator.analyze(
                                        item.getProduct().getId(),
                                        event.forceRefresh(),
                                        pureScoring.factorPopulation());
                        for (int index = 0; index < result.cacheHits(); index++) {
                            dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        }
                        warning = result.warning();
                        if (!result.analysisCompleted()) {
                            throw new IncompleteItemException(incompleteAnalysisMessage(warning));
                        }
                    }
                    case SCENE_CLASSIFY -> {
                        var response = sceneClassificationService.classify(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response != null && response.cacheHit() && dailyAiBudget != null) {
                            dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        }
                    }
                    case REVIEW_RISK -> {
                        var response = reviewRiskService.analyze(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response.cacheHit() && dailyAiBudget != null) dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        if (response.statusMessage() != null && !response.statusMessage().isBlank()) {
                            warning = response.statusMessage();
                        }
                        if (!response.analysisCompleted()) {
                            throw new IncompleteItemException(incompleteReviewMessage(warning));
                        }
                    }
                    case SELLING_POINT -> {
                        var response = productInsightService.analyze(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response.cacheHit() && dailyAiBudget != null) dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        if (!response.analysisCompleted()) {
                            throw new IncompleteItemException(
                                    incompleteInsightMessage(response.statusMessage()));
                        }
                    }
                    case RECOMMENDATION -> {
                        var response = recommendationService.recommend(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response != null && response.cacheHit() && dailyAiBudget != null) {
                            dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        }
                    }
                    case TREND_INTERPRET -> {
                        var response = trendInterpretationService.interpret(
                                item.getKeyword().getId(), event.forceRefresh());
                        if (response != null && response.cacheHit() && dailyAiBudget != null) {
                            dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        }
                    }
                    case SOURCING_SCOUT -> {
                        var response = sourcingScoutService.scout(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response != null && response.cacheHit() && dailyAiBudget != null) {
                            dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        }
                    }
                    case WEIGHT_CALIBRATION -> weightCalibrationService.interpret(
                            item.getCalibrationReport().getId(), event.forceRefresh());
                    default -> throw new IllegalStateException("尚未支援的 AI 任務類型");
                }
                warning = mergeWarnings(warning, AiExecutionWarningContext.consumeMessage());
                AiBudgetExecutionContext.Metrics metrics = AiBudgetExecutionContext.metrics();
                item.setStatus(metrics.requests() == 0 && metrics.cacheHits() > 0
                        ? TaskItemStatus.SKIPPED_CACHE : TaskItemStatus.SUCCEEDED);
                item.setErrorMessage(warning);
                successes++;
                if (task.getTaskType() == AiTaskType.FULL_ANALYSIS && metrics.requests() > 0) {
                    analyzedItems++;
                }
            } catch (DeferredItemException exception) {
                item.setStatus(TaskItemStatus.SKIPPED_QUOTA);
                item.setErrorMessage(exception.getMessage());
                failures++;
            } catch (AiBudgetExceededException exception) {
                quotaExhausted = task.getTaskType() == AiTaskType.FULL_ANALYSIS;
                item.setStatus(TaskItemStatus.SKIPPED_QUOTA);
                item.setErrorMessage(safeMessage(exception));
                failures++;
            } catch (RuntimeException exception) {
                item.setStatus(TaskItemStatus.FAILED);
                item.setErrorMessage(mergeWarnings(
                        safeMessage(exception), AiExecutionWarningContext.consumeMessage()));
                failures++;
            } finally {
                AiBudgetExecutionContext.Metrics metrics = AiBudgetExecutionContext.metrics();
                task.setRequestCount(task.getRequestCount() + metrics.requests());
                task.setRetryPoolRequestCount(
                        task.getRetryPoolRequestCount() + metrics.retryPoolRequests());
                task.setCacheHitCount(task.getCacheHitCount() + metrics.cacheHits());
                AiBudgetExecutionContext.clear();
                AiExecutionWarningContext.clear();
            }
            item.setDurationMs((int) Math.min(
                    Integer.MAX_VALUE, Duration.between(started, Instant.now()).toMillis()));
            itemRepository.save(item);
            if (wasCancelled(task)) return;
            task.setSuccessCount(successes);
            task.setFailCount(failures);
            taskRepository.save(task);
        }

        if (wasCancelled(task)) return;
        task.setSuccessCount(successes);
        task.setFailCount(failures);
        task.setStatus(failures == 0 ? TaskStatus.SUCCEEDED
                : successes == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);
    }

    private void settleInterruptedTask(Long taskId, RuntimeException cause) {
        try {
            AiTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null || task.getStatus() == TaskStatus.CANCELLED) return;
            List<AiTaskItem> items = itemRepository.findByTaskId(taskId);
            String message = safeText("任務執行中斷：" + safeMessage(cause));
            for (AiTaskItem item : items) {
                if (item.getStatus() != TaskItemStatus.PENDING) continue;
                item.setStatus(TaskItemStatus.FAILED);
                item.setErrorMessage(message);
                itemRepository.save(item);
            }
            int successes = successCount(items);
            int failures = failureCount(items);
            task.setSuccessCount(successes);
            task.setFailCount(failures);
            task.setStatus(failures == 0 ? TaskStatus.SUCCEEDED
                    : successes == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL);
            task.setFinishedAt(Instant.now());
            taskRepository.save(task);
        } catch (RuntimeException persistenceFailure) {
            log.error("Unable to settle interrupted AI task: taskId={}", taskId, persistenceFailure);
        } finally {
            AiBudgetExecutionContext.clear();
            AiExecutionWarningContext.clear();
        }
    }

    private boolean wasCancelled(AiTask task) {
        return taskRepository.findById(task.getId())
                .filter(current -> current.getStatus() == TaskStatus.CANCELLED)
                .map(current -> {
                    task.setStatus(TaskStatus.CANCELLED);
                    task.setFinishedAt(current.getFinishedAt());
                    return true;
                })
                .orElse(false);
    }

    private static int successCount(List<AiTaskItem> items) {
        return (int) items.stream()
                .filter(item -> item.getStatus() == TaskItemStatus.SUCCEEDED
                        || item.getStatus() == TaskItemStatus.SKIPPED_CACHE)
                .count();
    }

    private static int failureCount(List<AiTaskItem> items) {
        return (int) items.stream()
                .filter(item -> item.getStatus() == TaskItemStatus.FAILED
                        || item.getStatus() == TaskItemStatus.SKIPPED_QUOTA)
                .count();
    }

    private PureScoringPreparation preparePureScores(AiTask task, List<AiTaskItem> items) {
        if (task.getTaskType() != AiTaskType.FULL_ANALYSIS || pureScoringBatchService == null) {
            return new PureScoringPreparation(Map.of(), null);
        }
        List<Long> productIds = items.stream()
                .map(AiTaskItem::getProduct)
                .filter(java.util.Objects::nonNull)
                .map(Product::getId)
                .toList();
        try {
            PureScoringBatchService.BatchResult result = pureScoringBatchService.evaluateProductIds(
                    productIds, Instant.now());
            if (result == null || result.failures().isEmpty()) {
                return new PureScoringPreparation(
                        Map.of(), result == null ? null : result.factorPopulation());
            }
            Map<Long, String> failures = new LinkedHashMap<>();
            result.failures().forEach(failure -> failures.put(
                    failure.productId(), pureScoringFailureMessage(failure.message())));
            return new PureScoringPreparation(failures, result.factorPopulation());
        } catch (RuntimeException exception) {
            log.warn("Pre-analysis pure scoring failed for taskId={}", task.getId(), exception);
            String message = pureScoringFailureMessage(safeMessage(exception));
            Map<Long, String> failures = new LinkedHashMap<>();
            productIds.forEach(productId -> failures.put(productId, message));
            return new PureScoringPreparation(failures, null);
        }
    }

    private record PureScoringPreparation(
            Map<Long, String> failures, PreparedPopulation factorPopulation) {}

    private static String pureScoringFailureMessage(String message) {
        String detail = message == null || message.isBlank() ? "未知錯誤" : message;
        return safeText("純評分失敗：" + detail);
    }

    private static String safeText(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "AI 任務執行失敗";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String mergeWarnings(String current, String added) {
        if (current == null || current.isBlank()) return added;
        if (added == null || added.isBlank()) return current;
        String merged = current + " " + added;
        return merged.length() <= 500 ? merged : merged.substring(0, 500);
    }

    private static String incompleteInsightMessage(String message) {
        return message == null || message.isBlank()
                ? "賣點與風險分析未完成"
                : message;
    }

    private static String incompleteReviewMessage(String message) {
        return message == null || message.isBlank()
                ? "評論風險分析未完成"
                : message;
    }

    private static String incompleteAnalysisMessage(String message) {
        return message == null || message.isBlank()
                ? "完整分析未完成"
                : message;
    }

    private static final class IncompleteItemException extends RuntimeException {
        private IncompleteItemException(String message) {
            super(message);
        }
    }

    private static final class PureScoringFailedException extends RuntimeException {
        private PureScoringFailedException(String message) {
            super(message);
        }
    }

    private static final class DeferredItemException extends RuntimeException {
        private DeferredItemException(String message) {
            super(message);
        }
    }
}
