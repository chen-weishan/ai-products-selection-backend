package com.example.ssds.api.weight.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;

import jakarta.validation.constraints.NotNull;

public record SceneGroupRequest(
        @NotNull SceneType sceneType,

        @NotNull Map<FactorCode, @NotNull BigDecimal> weights,

        @NotNull BigDecimal gradeAMin,
        @NotNull BigDecimal gradeBMin) {
}
