package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 §8.2 GET /dashboard/todos 回應：待辦提示（含待回填結案）。
 */
public record DashboardTodosResponseDto(
        List<OverdueCampaignDto> overdueCampaigns) {
}