package com.example.ssds.api.aitask;

import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AiTaskWorker {
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository itemRepository;
    private final SceneClassificationService sceneClassificationService;

    public AiTaskWorker(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            SceneClassificationService sceneClassificationService) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.sceneClassificationService = sceneClassificationService;
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
            item.setStatus(TaskStatus.RUNNING);
            itemRepository.save(item);
            try {
                sceneClassificationService.classify(item.getProduct().getId(), event.forceRefresh());
                item.setStatus(TaskStatus.SUCCESS);
                item.setErrorMessage(null);
                successes++;
            } catch (RuntimeException exception) {
                item.setStatus(TaskStatus.FAILED);
                item.setErrorMessage(safeMessage(exception));
                failures++;
            }
            item.setDurationMs((int) Math.min(
                    Integer.MAX_VALUE, Duration.between(started, Instant.now()).toMillis()));
            itemRepository.save(item);
            task.setSuccessCount(successes);
            task.setFailCount(failures);
            taskRepository.save(task);
        }

        task.setStatus(failures == 0 ? TaskStatus.SUCCESS
                : successes == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "AI 任務執行失敗";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
