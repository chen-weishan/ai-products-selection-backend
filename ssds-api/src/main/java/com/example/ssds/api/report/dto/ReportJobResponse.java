package com.example.ssds.api.report.dto;

import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ReportJob;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record ReportJobResponse(
        Long id,
        ReportType reportType,
        ReportFormat format,
        Map<String, Object> params,
        TaskStatus status,
        String fileName,
        Long fileSize,
        Integer rowCount,
        Instant requestedAt,
        Instant finishedAt,
        boolean downloadable
) {
    public static ReportJobResponse from(ReportJob job, ObjectMapper mapper) {
        Map<String, Object> params;
        try {
            params = mapper.readValue(job.getParamsJson(), new TypeReference<>() {});
        } catch (Exception ignored) {
            params = Map.of();
        }
        Path file = job.getFilePath() == null ? null : Path.of(job.getFilePath());
        boolean downloadable = job.getStatus() == TaskStatus.SUCCEEDED
                && file != null && Files.isRegularFile(file);
        Long size = null;
        if (downloadable) {
            try {
                size = Files.size(file);
            } catch (java.io.IOException ignored) {
                downloadable = false;
            }
        }
        return new ReportJobResponse(
                job.getId(), job.getReportType(), job.getFormat(), params, job.getStatus(),
                file == null ? null : file.getFileName().toString(), size, job.getRowCount(),
                job.getRequestedAt(), job.getFinishedAt(), downloadable);
    }
}
