package com.example.ssds.api.scene.dto;

import com.example.ssds.core.domain.SceneType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SceneOverrideRequest(
        @NotNull(message = "覆寫情境不可為空") SceneType sceneType,
        @NotBlank(message = "覆寫理由不可為空")
        @Size(max = 255, message = "覆寫理由不可超過 255 字") String reason) {}
