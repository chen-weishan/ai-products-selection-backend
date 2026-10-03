package com.example.ssds.api.imports.dto;

import com.example.ssds.core.domain.ImportDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ImportMappingTemplateRequest(
        @NotBlank(message = "範本名稱不可空白")
        @Size(max = 100, message = "範本名稱不可超過 100 字")
        String name,

        @NotNull(message = "資料類型不可空白")
        ImportDataType dataType,

        @NotEmpty(message = "欄位對應不可空白")
        Map<@NotBlank(message = "來源欄位不可空白") String,
                @NotBlank(message = "系統欄位不可空白") String> mappings
) {}
