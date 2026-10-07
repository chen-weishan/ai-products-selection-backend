package com.example.ssds.api.report.dto;

import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ReportGenerateRequest(
        @NotNull ReportType reportType,
        @NotNull ReportFormat format,
        Map<String, Object> params
) {
    public ReportGenerateRequest {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
