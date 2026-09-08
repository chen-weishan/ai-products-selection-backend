package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.infra.entity.AiTaskItem;

public record AiTaskItemResponse(
        Long itemId,
        Long productId,
        Long keywordId,
        TaskItemStatus status,
        String errorMessage,
        Integer durationMs
) {
    public static AiTaskItemResponse from(AiTaskItem item) {
        return new AiTaskItemResponse(
                item.getId(),
                item.getProduct() == null ? null : item.getProduct().getId(),
                item.getKeyword() == null ? null : item.getKeyword().getId(),
                item.getStatus(),
                item.getErrorMessage(),
                item.getDurationMs());
    }
}
