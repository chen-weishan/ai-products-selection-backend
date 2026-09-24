package com.example.ssds.api.festival.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CategoryLeadTimeUpdateRequest(
        @NotNull @Min(0) @Max(365) Integer leadTimeDays) {

}
