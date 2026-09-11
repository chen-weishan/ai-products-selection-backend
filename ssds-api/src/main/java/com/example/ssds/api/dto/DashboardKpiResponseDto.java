package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 §8.2 GET /dashboard/summary 回應：KPI 四項。
 */
public record DashboardKpiResponseDto(
        KpiDto kpi,
        boolean scoringExecuted) {
}