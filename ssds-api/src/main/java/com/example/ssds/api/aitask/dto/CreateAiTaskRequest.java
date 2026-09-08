package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.AiTaskType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAiTaskRequest(
        @NotNull AiTaskType taskType,
        @Size(max = 150) List<@NotNull Long> productIds,
        @Size(max = 100) List<@NotNull Long> keywordIds,
        @Valid Options options
) {
    public CreateAiTaskRequest(AiTaskType taskType, List<Long> productIds, Options options) {
        this(taskType, productIds, List.of(), options);
    }

    public CreateAiTaskRequest {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        keywordIds = keywordIds == null ? List.of() : List.copyOf(keywordIds);
    }

    public boolean forceRefresh() {
        return options != null && options.forceRefresh();
    }

    public record Options(boolean forceRefresh) {}
}
