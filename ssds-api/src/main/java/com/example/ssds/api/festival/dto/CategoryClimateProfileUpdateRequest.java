package com.example.ssds.api.festival.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record CategoryClimateProfileUpdateRequest(
        @NotNull BigDecimal idealTempMin,
        @NotNull BigDecimal idealTempMax,
        BigDecimal tolerance // null 代表沿用系統預設（ClimateProperties）
) {

}
