package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 §8.2 GET /dashboard/rankings 回應：四榜各 Top N。
 */
public record DashboardRankingsResponseDto(
        List<RankingItemDto> viral,
        List<RankingItemDto> festival,
        List<RankingItemDto> replenishment,
        List<RankingItemDto> seasonal) {
}