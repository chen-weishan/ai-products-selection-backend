package com.example.ssds.api.aitask.execution;

import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 應用重啟後接續尚未收斂的 AI 任務；worker 會略過已完成 items。 */
@Component
public class AiTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(AiTaskRecovery.class);

    private final AiTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AiTaskRecovery(
            AiTaskRepository taskRepository,
            ApplicationEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        Map<Long, AiTask> interrupted = new LinkedHashMap<>();
        List.of(TaskStatus.PENDING, TaskStatus.RUNNING).forEach(status ->
                taskRepository.findByStatus(status).forEach(task -> interrupted.put(task.getId(), task)));
        interrupted.values().forEach(task -> {
            log.warn("Recovering interrupted AI task: taskId={}, status={}", task.getId(), task.getStatus());
            // forceRefresh 沒有持久化；恢復時允許沿用已落庫快取，避免重複消耗配額。
            eventPublisher.publishEvent(new AiTaskCreatedEvent(task.getId(), false));
        });
    }
}
