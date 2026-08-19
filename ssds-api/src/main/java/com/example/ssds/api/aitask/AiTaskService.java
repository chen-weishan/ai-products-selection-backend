package com.example.ssds.api.aitask;

import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.exception.BusinessException;
import com.example.ssds.api.exception.ErrorCode;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskService {
    private final AiTaskRepository taskRepository;
    private final AiTaskItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskItemRepository itemRepository,
            ProductRepository productRepository,
            ApplicationEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AiTaskResponse create(CreateAiTaskRequest request) {
        if (request.taskType() != AiTaskType.SCENE_CLASSIFY) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "目前 A 軌只開放 SCENE_CLASSIFY 任務");
        }
        List<Long> distinctIds = request.productIds().stream().distinct().toList();
        List<Product> products = productRepository.findAllById(distinctIds);
        if (products.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "部分品項不存在");
        }
        if (products.stream().anyMatch(product -> product.getTrackType() != TrackType.A)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "SCENE_CLASSIFY 任務只能包含 A 軌品項");
        }

        AiTask task = taskRepository.save(AiTask.builder()
                .taskType(request.taskType())
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
