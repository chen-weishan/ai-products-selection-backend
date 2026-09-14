package com.example.ssds.api.imports.dto;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ImportBatch;
import java.time.Instant;

public record ImportBatchResponse(
        Long batchId,
        ImportDataType dataType,
        String fileName,
        Long fileSize,
        int totalRows,
        int successRows,
        int failRows,
        int processedRows,
        int progressPercent,
        TaskStatus status,
        boolean async,
        String createdBy,
        Instant createdAt,
        Instant finishedAt
) {
    public static ImportBatchResponse from(ImportBatch batch) {
        int processed = batch.getSuccessRows() + batch.getFailRows();
        int progress = batch.getTotalRows() == 0
                ? 0
                : Math.min(100, (int) ((long) processed * 100 / batch.getTotalRows()));
        return new ImportBatchResponse(
                batch.getId(), batch.getDataType(), batch.getFileName(), batch.getFileSize(),
                batch.getTotalRows(), batch.getSuccessRows(), batch.getFailRows(), processed,
                progress, batch.getStatus(), batch.isAsync(),
                batch.getCreatedBy() == null ? null : batch.getCreatedBy().getEmail(),
                batch.getCreatedAt(), batch.getFinishedAt());
    }
}
