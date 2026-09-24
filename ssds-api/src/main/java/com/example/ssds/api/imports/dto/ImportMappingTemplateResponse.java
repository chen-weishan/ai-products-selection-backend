package com.example.ssds.api.imports.dto;

import com.example.ssds.core.domain.ImportDataType;
import java.time.OffsetDateTime;
import java.util.Map;

public record ImportMappingTemplateResponse(
        Long id,
        String name,
        ImportDataType dataType,
        Map<String, String> mappings,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
