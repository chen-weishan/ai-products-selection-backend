package com.example.ssds.api.aitask.execution;

import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 應用重啟後接續尚未收斂的 AI 任務；worker 會略過已完成 items。 */
@Component
public class AiTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(AiTaskRecovery.class);

    private final AiTaskRepository taskRepository;
    private final AiTaskWorker worker;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean pollingEnabled;

    public AiTaskRecovery(
            AiTaskRepository taskRepository,
            AiTaskWorker worker,
            ApplicationEventPublisher eventPublisher,
            @Value("${ai.task.recovery-polling-enabled:false}") boolean pollingEnabled) {
        this.taskRepository = taskRepository;
        this.worker = worker;
        this.eventPublisher = eventPublisher;
        this.pollingEnabled = pollingEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        recoverLostTasks();
    }

    @Scheduled(
            fixedDelayString = "${ai.task.recovery-poll-seconds:300}",
            initialDelayString = "${ai.task.recovery-poll-seconds:300}",
            timeUnit = TimeUnit.SECONDS)
    public void pollInterruptedTasks() {
        if (!pollingEnabled) return;
        recoverLostTasks();
    }

    private void recoverLostTasks() {
        Map<Long, AiTask> interrupted = new LinkedHashMap<>();
        List.of(TaskStatus.PENDING, TaskStatus.RUNNING).forEach(status ->
                taskRepository.findByStatus(status).forEach(task -> interrupted.put(task.getId(), task)));
        interrupted.values().forEach(task -> {
            if (worker.isTaskActive(task.getId())) {
                log.debug("AI task is still active; recovery skipped: taskId={}, status={}",
                        task.getId(), task.getStatus());
                return;
            }
            log.warn("Recovering interrupted AI task: taskId={}, status={}", task.getId(), task.getStatus());
            // forceRefresh 沒有持久化；恢復時允許沿用已落庫快取，避免重複消耗配額。
            eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), false));
        });
    }
}
