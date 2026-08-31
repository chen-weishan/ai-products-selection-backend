package com.example.ssds.api.aitask;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.ai.client.AiExecutionWarningContext;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService,
            TrendInterpretationService trendInterpretationService,
            SourcingScoutService sourcingScoutService) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.sceneClassificationService = sceneClassificationService;
        this.reviewRiskService = reviewRiskService;
        this.productInsightService = productInsightService;
        this.recommendationService = recommendationService;
        this.trendInterpretationService = trendInterpretationService;
        this.sourcingScoutService = sourcingScoutService;
    }

    AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService,
            ReviewRiskService reviewRiskService,
            ProductInsightService productInsightService,
            RecommendationService recommendationService) {
        this(taskRepository, itemRepository, sceneClassificationService, reviewRiskService,
                productInsightService, recommendationService, null, null);
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
                productInsightService, recommendationService, trendInterpretationService, null);
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
        for (AiTaskItem item : itemRepository.findByTaskId(task.getId())) {
            Instant started = Instant.now();
            AiExecutionWarningContext.clear();
            try {
                String warning = null;
                switch (task.getTaskType()) {
                    case SCENE_CLASSIFY -> sceneClassificationService.classify(
                            item.getProduct().getId(), event.forceRefresh());
                    case REVIEW_RISK -> {
                        var response = reviewRiskService.analyze(
                                item.getProduct().getId(), event.forceRefresh());
                        if (response.fallbackApplied()) {
                            warning = "評論分析未完成";
                        }
                    }
                    case SELLING_POINT -> {
                        var response = productInsightService.analyze(
                                item.getProduct().getId(), event.forceRefresh());
                        if (!response.analysisCompleted()) {
                            warning = response.statusMessage();
                        }
                    }
                    case RECOMMENDATION -> recommendationService.recommend(
                            item.getProduct().getId(), event.forceRefresh());
                    case TREND_INTERPRET -> trendInterpretationService.interpret(
                            item.getKeyword().getId(), event.forceRefresh());
                    case SOURCING_SCOUT -> sourcingScoutService.scout(
                            item.getProduct().getId(), event.forceRefresh());
                    default -> throw new IllegalStateException("尚未支援的 AI 任務類型");
                }
                warning = mergeWarnings(warning, AiExecutionWarningContext.consumeMessage());
                item.setStatus(TaskItemStatus.SUCCEEDED);
                item.setErrorMessage(warning);
                successes++;
            } catch (RuntimeException exception) {
                item.setStatus(TaskItemStatus.FAILED);
                item.setErrorMessage(mergeWarnings(
                        safeMessage(exception), AiExecutionWarningContext.consumeMessage()));
                failures++;
            } finally {
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
}
