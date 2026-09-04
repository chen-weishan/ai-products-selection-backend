package com.example.ssds.api.aitask.service;

import com.example.ssds.api.aitask.dto.AiTaskStatusResponse;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.repository.AiTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI 任務唯讀查詢；目前先供 FR-03 清單輪詢評分結果。 */
@Service
@Transactional(readOnly = true)
public class AiTaskQueryService {

    private final AiTaskRepository taskRepository;

    public AiTaskQueryService(AiTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public AiTaskStatusResponse getStatus(Long taskId) {
        AiTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的 AI 任務：" + taskId
                ));
        return new AiTaskStatusResponse(
                task.getId(),
                task.getTaskType(),
                task.getStatus(),
                task.getTotalCount(),
                task.getSuccessCount(),
                task.getFailCount(),
                task.progressPercent(),
                task.getStartedAt(),
                task.getFinishedAt()
        );
    }
}
