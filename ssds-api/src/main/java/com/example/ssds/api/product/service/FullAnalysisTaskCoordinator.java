package com.example.ssds.api.product.service;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 以短交易領取、失敗標記及結束 FULL_ANALYSIS 任務。 */
@Service
public class FullAnalysisTaskCoordinator {

    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository itemRepository;

    public FullAnalysisTaskCoordinator(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository
    ) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long claimNext() {
        List<AiTask> tasks = taskRepository.findNextForUpdate(
                AiTaskType.FULL_ANALYSIS,
                TaskStatus.PENDING,
                PageRequest.of(0, 1)
        );
        if (tasks.isEmpty()) return null;
        AiTask task = tasks.getFirst();
        task.setStatus(TaskStatus.RUNNING);
        task.setStartedAt(Instant.now());
        return task.getId();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> pendingItemIds(Long taskId) {
        return itemRepository.findIdsByTaskIdAndStatus(taskId, TaskItemStatus.PENDING);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long itemId, Throwable error, int durationMs) {
        AiTaskItem item = itemRepository.findById(itemId).orElseThrow();
        item.setStatus(TaskItemStatus.FAILED);
        item.setDurationMs(durationMs);
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        item.setErrorMessage(message.substring(0, Math.min(message.length(), 500)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        int succeeded = Math.toIntExact(
                itemRepository.countByTaskIdAndStatus(taskId, TaskItemStatus.SUCCEEDED)
        );
        int failed = Math.toIntExact(
                itemRepository.countByTaskIdAndStatus(taskId, TaskItemStatus.FAILED)
        );
        task.setSuccessCount(succeeded);
        task.setFailCount(failed);
        task.setStatus(failed == 0
                ? TaskStatus.SUCCEEDED
                : succeeded == 0 ? TaskStatus.FAILED : TaskStatus.PARTIAL);
        task.setFinishedAt(Instant.now());
    }
}
