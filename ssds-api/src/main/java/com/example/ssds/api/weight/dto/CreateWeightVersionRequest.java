package com.example.ssds.api.weight.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWeightVersionRequest(
        @NotBlank(message = "版本號不可為空") @Size(max = 16) String versionNo,

        @NotBlank @Size(max = 80) String name,

        @Size(max = 512) String changeNote,

        @NotNull @Size(min = 4, max = 4, message = "必須提供四榜的設定") @Valid List<SceneGroupRequest> sceneGroups) {
}