package com.example.ssds.api.aitask.dto;

import com.example.ssds.core.domain.AiTaskType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAiTaskRequest(
        @NotNull AiTaskType taskType,
        @NotEmpty @Size(max = 100) List<@NotNull Long> productIds,
        @Valid Options options
) {
    public boolean forceRefresh() {
        return options != null && options.forceRefresh();
    }

    public record Options(boolean forceRefresh) {}
}
