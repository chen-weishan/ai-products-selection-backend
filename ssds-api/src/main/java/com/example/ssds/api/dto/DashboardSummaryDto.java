package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 儀表板完整回應（組合全部區塊）。
 *
 * @param kpi             KPI 四項數字
 * @param viralRanking    話題爆款榜 Top 5
 * @param festivalRanking 節慶檔期榜 Top 5
 * @param restockRanking  常態補貨榜 Top 5
 * @param seasonalRanking 季節導向榜 Top 5
 * @param heatSources     熱度來源狀態列
 */
public record DashboardSummaryDto(
                KpiDto kpi,
                List<RankingItemDto> viralRanking,
                List<RankingItemDto> festivalRanking,
                List<RankingItemDto> restockRanking,
                List<RankingItemDto> seasonalRanking,
                List<HeatSourceDto> heatSources) {
}