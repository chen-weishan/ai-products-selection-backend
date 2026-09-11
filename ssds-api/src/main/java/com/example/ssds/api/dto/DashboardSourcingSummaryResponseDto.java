package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 §8.2 GET /dashboard/sourcing-summary 回應：B 軌摘要（依時效落差升冪）。
 */
public record DashboardSourcingSummaryResponseDto(
        List<BtrackSummaryDto> items) {
}