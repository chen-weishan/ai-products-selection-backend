package com.example.ssds.api.calibration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record WeightCalibrationInterpretRequest(
        @NotNull @Valid SceneOverrideStatistics sceneOverrides,
        boolean forceRefresh) {
    public record SceneOverrideStatistics(
            @PositiveOrZero int totalClassifications,
            @PositiveOrZero int overrideCount,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal overrideRate,
            @NotNull @Size(max=20) List<@Valid CategoryOverrideStatistic> concentratedCategories) {}
    public record CategoryOverrideStatistic(
            @NotBlank @Size(max=50) String category,
            @PositiveOrZero int totalClassifications,
            @PositiveOrZero int overrideCount,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal overrideRate) {}
}
