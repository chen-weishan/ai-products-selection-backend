package com.example.ssds.api.aitask;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.ai.client.AiExecutionWarningContext;
import com.example.ssds.ai.client.AiBudgetExceededException;
import com.example.ssds.ai.client.AiBudgetExecutionContext;
import com.example.ssds.ai.client.DailyAiBudget;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AiTaskWorker {
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository itemRepository;
    private final SceneClassificationService sceneClassificationService;
    private final ReviewRiskService reviewRiskService;
    private final ProductInsightService productInsightService;
    private final RecommendationService recommendationService;
    private final TrendInterpretationService trendInterpretationService;
    private final SourcingScoutService sourcingScoutService;
    private final FullAnalysisOrchestrator fullAnalysisOrchestrator;
    private final DailyAiBudget dailyAiBudget;
    private final int batchItemCap;

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
                productInsightService, recommendationService, null, null, null, null, 150);
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
                productInsightService, recommendationService, trendInterpretationService, null, null, null, 150);
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
                sourcingScoutService, null, null, 150);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void run(AiTaskCreatedEvent event) {
        AiTask task = taskRepository.findById(event.taskId()).orElseThrow();
        task.setStatus(TaskStatus.RUNNING);
        task.setStartedAt(Instant.now());
        taskRepository.save(task);

        int successes = 0;
        int failures = 0;
        int analyzedItems = 0;
        boolean quotaExhausted = false;
        for (AiTaskItem item : itemRepository.findByTaskId(task.getId())) {
            Instant started = Instant.now();
            AiExecutionWarningContext.clear();
            AiBudgetExecutionContext.begin(task.getBudgetPool());
            try {
                if (task.getTaskType() == AiTaskType.FULL_ANALYSIS
                        && (quotaExhausted || analyzedItems >= batchItemCap)) {
                    throw new DeferredItemException(quotaExhausted
                            ? "TRACK_A 今日配額已耗盡，已排入隔日續跑"
                            : "已達單輪 AI 分析品項上限，已排入隔日續跑");
                }
                String warning = null;
                switch (task.getTaskType()) {
                    case FULL_ANALYSIS -> {
                        FullAnalysisOrchestrator.Result result = fullAnalysisOrchestrator.analyze(
                                item.getProduct().getId(), event.forceRefresh());
                        for (int index = 0; index < result.cacheHits(); index++) {
                            dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        }
                        warning = result.warning();
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
                    }
                    case SELLING_POINT -> {
                        var response = productInsightService.analyze(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response.cacheHit() && dailyAiBudget != null) dailyAiBudget.recordCacheHit(task.getBudgetPool());
                        if (!response.analysisCompleted()) {
                            warning = response.statusMessage();
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
                    case SOURCING_SCOUT -> sourcingScoutService.scout(
                            item.getProduct().getId(), event.forceRefresh());
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
            task.setSuccessCount(successes);
            task.setFailCount(failures);
            taskRepository.save(task);
        }

        task.setStatus(failures == 0 ? TaskStatus.SUCCEEDED
                : successes == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);
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

    private static final class DeferredItemException extends RuntimeException {
        private DeferredItemException(String message) {
            super(message);
        }
    }
}
