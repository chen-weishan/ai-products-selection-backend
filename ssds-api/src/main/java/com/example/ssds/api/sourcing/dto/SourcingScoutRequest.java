package com.example.ssds.api.sourcing.dto;

import jakarta.validation.constraints.*;

public record SourcingScoutRequest(
        @NotBlank @Size(max = 80) String keyword,
        @NotNull Long categoryId,
        boolean forceRefresh) {}
