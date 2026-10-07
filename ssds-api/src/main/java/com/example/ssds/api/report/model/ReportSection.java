package com.example.ssds.api.report.model;

import java.util.List;
import java.util.Map;

public record ReportSection(
        String title,
        List<ReportColumn> columns,
        List<Map<String, Object>> rows
) {
    public ReportSection {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
