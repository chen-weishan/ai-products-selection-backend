package com.example.ssds.api.score.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SimulateRequest(
        @NotNull Long weightVersionId,
        @NotBlank String period,                                   // 規格 §8.2 未列，補上
        SceneType scene,                                           // 可為 null＝四榜混排
        Long categoryId,                                           // 可為 null
        Map<SceneType, Map<FactorCode, BigDecimal>> overrides,     // 可為 null＝用版本原值
        Map<SceneType, ThresholdOverride> thresholdOverrides,      // 可為 null
        @Min(value = 1, message = "limit 至少為 1") Integer limit) {                                           // 可為 null＝用預設

    public record ThresholdOverride(BigDecimal gradeAMin, BigDecimal gradeBMin) {}
}
