package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTaskItem;

public record AiTaskItemResponse(
        Long itemId,
        Long productId,
        TaskStatus status,
        String errorMessage,
        Integer durationMs
) {
    public static AiTaskItemResponse from(AiTaskItem item) {
        return new AiTaskItemResponse(
                item.getId(),
                item.getProduct() == null ? null : item.getProduct().getId(),
                item.getStatus(),
                item.getErrorMessage(),
                item.getDurationMs());
    }
}
