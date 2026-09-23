package com.example.ssds.api.imports.dto;

import com.example.ssds.core.domain.ImportDataType;
import java.util.List;

public record ImportPreviewResponse(
        Long batchId,
        ImportDataType dataType,
        int totalRows,
        int validRows,
        int errorRows,
        int duplicateRows,
        boolean async,
        List<ImportPreviewRow> previewRows,
        List<AudienceChange> audienceChanges
) {
    public record AudienceChange(String category, java.util.Map<String,java.math.BigDecimal> before,
            java.util.Map<String,java.math.BigDecimal> after) {}
}
