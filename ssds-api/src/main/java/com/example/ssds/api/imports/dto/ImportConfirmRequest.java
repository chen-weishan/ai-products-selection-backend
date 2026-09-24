package com.example.ssds.api.imports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

public record ImportConfirmRequest(
        @NotEmpty(message = "欄位對應不可空白")
        Map<@NotBlank(message = "來源欄位不可空白") String,
                @NotBlank(message = "系統欄位不可空白") String> mappings
) {}
