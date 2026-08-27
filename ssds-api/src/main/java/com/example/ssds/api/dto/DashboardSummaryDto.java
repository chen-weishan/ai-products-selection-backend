package com.example.ssds.api.dto;

import java.util.List;

/**
 * FR-02 儀表板完整回應（組合全部區塊）。
 *
 * @param kpi              KPI 四項數字（A 級主推已依品項去重、高風險僅計 severity=HIGH）
 * @param viralRanking     話題爆款榜 Top 5
 * @param festivalRanking  節慶檔期榜 Top 5
 * @param restockRanking   常態補貨榜 Top 5
 * @param seasonalRanking  季節導向榜 Top 5
 * @param heatSources      熱度來源狀態列
 * @param overdueCampaigns 待回填結案清單
 * @param bTrackSummary    B 軌摘要（依時效落差升冪，含已淘汰者供灰底顯示）
 * @param scoringExecuted  該週期是否已有有效評分快照；false 時前端顯示
 *                         「本週尚未執行評分」空狀態（FR-02）
 */
public record DashboardSummaryDto(
                KpiDto kpi,
                List<RankingItemDto> viralRanking,
                List<RankingItemDto> festivalRanking,
                List<RankingItemDto> restockRanking,
                List<RankingItemDto> seasonalRanking,
                List<HeatSourceDto> heatSources,
                List<OverdueCampaignDto> overdueCampaigns,
                List<BtrackSummaryDto> bTrackSummary,
                boolean scoringExecuted) {
}