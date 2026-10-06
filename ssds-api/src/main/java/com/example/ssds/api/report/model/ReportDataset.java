package com.example.ssds.api.report.model;

import java.util.List;

public record ReportDataset(
        String title,
        String subtitle,
        List<ReportSection> sections,
        int rowCount
) {
    public ReportDataset {
        sections = List.copyOf(sections);
    }
}
