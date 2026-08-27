package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 §8.2 GET /dashboard/heat-sources 回應：熱度來源狀態摘要。
 */
public record DashboardHeatSourcesResponseDto(
        List<HeatSourceDto> items) {
}