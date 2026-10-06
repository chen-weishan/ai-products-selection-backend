package com.example.ssds.api.report.dto;

import java.util.List;

/** FR-12 報表條件下拉選單所需的唯讀資料。 */
public record ReportFilterOptionsResponse(
        List<CategoryOption> categories,
        List<DecisionMakerOption> decisionMakers,
        List<String> scorePeriods,
        List<String> calibrationQuarters
) {
    public record CategoryOption(Long id, String label) {}

    public record DecisionMakerOption(Long id, String displayName, String email) {}
}
