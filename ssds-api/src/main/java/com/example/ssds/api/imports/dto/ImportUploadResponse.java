package com.example.ssds.api.imports.dto;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.ingest.importer.ImportColumnSuggestion;
import java.util.List;
import java.util.Map;

public record ImportUploadResponse(
        Long batchId,
        ImportDataType dataType,
        String fileName,
        long fileSize,
        int totalRows,
        boolean async,
        List<String> headers,
        List<ImportColumnSuggestion> mappingSuggestions,
        Map<String, String> savedMappings
) {}
