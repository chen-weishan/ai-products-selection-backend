package com.example.ssds.api.weight.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record ApproveWeightVersionRequest(
        @NotNull LocalDate effectiveFrom) {

}
