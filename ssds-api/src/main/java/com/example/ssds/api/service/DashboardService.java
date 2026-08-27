package com.example.ssds.api.service;

import com.example.ssds.api.dto.BtrackSummaryDto;
import com.example.ssds.api.dto.DashboardHeatSourcesResponseDto;
import com.example.ssds.api.dto.DashboardKpiResponseDto;
import com.example.ssds.api.dto.DashboardRankingsResponseDto;
import com.example.ssds.api.dto.DashboardSourcingSummaryResponseDto;
import com.example.ssds.api.dto.DashboardTodosResponseDto;
import com.example.ssds.api.dto.HeatSourceDto;
import com.example.ssds.api.dto.KpiDto;
import com.example.ssds.api.dto.OverdueCampaignDto;
import com.example.ssds.api.dto.RankingItemDto;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DashboardService {

        /** §8.1：回應一律以 +08:00 呈現。 */
        private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Taipei");

        /** FR-02：B 軌摘要 Top 3（§8.2 sourcing-summary 預設 limit=3）。 */
        private static final int B_TRACK_SUMMARY_LIMIT = 3;

        /** FR-02：各榜顯示 Top 5。 */
        private static final int RANKING_LIMIT = 5;

        private final ProductRepository productRepository;
        private final ProductScoreRepository productScoreRepository;
        private final RiskAlertRepository riskAlertRepository;
        private final HeatSourceRepository heatSourceRepository;
        private final DecisionRecordRepository decisionRecordRepository;
        private final SourcingCandidateRepository sourcingCandidateRepository;
        private final SceneClassificationLogRepository sceneClassificationLogRepository;

        /** §8.2 GET /dashboard/summary */
        public DashboardKpiResponseDto getKpi(String period, String track) {
                TrackType trackType = "B".equals(track) ? TrackType.B : TrackType.A;
                long totalCandidates = productRepository.countByTrackType(trackType);

                KpiDto kpi = new KpiDto(
                                totalCandidates,
                                productScoreRepository.countAGradeByPeriod(period),
                                riskAlertRepository.countByStatusAndSeverity(AlertStatus.OPEN, Severity.HIGH),
                                getOverdueCampaigns().size());

                boolean scoringExecuted = productScoreRepository.existsByPeriodAndActiveTrue(period);

                return new DashboardKpiResponseDto(kpi, scoringExecuted);
        }

        /** §8.2 GET /dashboard/rankings */
        public DashboardRankingsResponseDto getRankings(String period, String track, String scene, Integer limit) {
                int rankingLimit = limit != null ? limit : RANKING_LIMIT;
                TrackType trackType = "B".equals(track) ? TrackType.B : TrackType.A;

                List<ProductScore> viralScores = List.of();
                List<ProductScore> festivalScores = List.of();
                List<ProductScore> restockScores = List.of();
                List<ProductScore> seasonalScores = List.of();

                if (trackType == TrackType.A) {
                        Pageable topN = PageRequest.of(0, rankingLimit);

                        if (scene == null || "VIRAL".equals(scene)) {
                                viralScores = productScoreRepository.findTopByPeriodAndSceneType(period, "VIRAL", topN);
                        }
                        if (scene == null || "FESTIVAL".equals(scene)) {
                                festivalScores = productScoreRepository.findTopByPeriodAndSceneType(period, "FESTIVAL",
                                                topN);
                        }
                        if (scene == null || "REPLENISHMENT".equals(scene)) {
                                restockScores = productScoreRepository.findTopByPeriodAndSceneType(period,
                                                "REPLENISHMENT", topN);
                        }
                        if (scene == null || "SEASONAL".equals(scene)) {
                                seasonalScores = productScoreRepository.findTopByPeriodAndSceneType(period, "SEASONAL",
                                                topN);
                        }
                }

                Map<Long, SceneClassificationLog> latestLogs = loadLatestClassificationLogs(
                                List.of(viralScores, festivalScores, restockScores, seasonalScores));

                // FR-02: 風險指示 - 取得所有排行品項的最高嚴重度風險
                Set<Long> allProductIds = new HashSet<>();
                for (List<ProductScore> board : List.of(viralScores, festivalScores, restockScores, seasonalScores)) {
                        board.forEach(s -> allProductIds.add(s.getProduct().getId()));
                }
                Map<Long, Severity> riskMap = allProductIds.isEmpty() ? Map.of()
                                : riskAlertRepository.findMaxSeverityByProductIds(new ArrayList<>(allProductIds));

                return new DashboardRankingsResponseDto(
                                toRankingItems(viralScores, latestLogs, riskMap),
                                toRankingItems(festivalScores, latestLogs, riskMap),
                                toRankingItems(restockScores, latestLogs, riskMap),
                                toRankingItems(seasonalScores, latestLogs, riskMap));
        }

        /** §8.2 GET /dashboard/sourcing-summary */
        public DashboardSourcingSummaryResponseDto getSourcingSummary(Integer limit) {
                int sourcingLimit = limit != null ? limit : B_TRACK_SUMMARY_LIMIT;
                List<SourcingCandidate> candidates = sourcingCandidateRepository
                                .findDashboardSummaryCandidates(PageRequest.of(0, sourcingLimit));

                List<BtrackSummaryDto> items = candidates.stream()
                                .map(sc -> new BtrackSummaryDto(
                                                sc.getProduct().getId(),
                                                sc.getProduct().getName(),
                                                sc.getHeatStage() != null ? sc.getHeatStage().name() : null,
                                                sc.getTimeGapDays(),
                                                sc.getProduct().getSourcingStatus().name()))
                                .toList();

                return new DashboardSourcingSummaryResponseDto(items);
        }

        /** §8.2 GET /dashboard/todos */
        public DashboardTodosResponseDto getTodos() {
                List<OverdueCampaignDto> overdueCampaigns = getOverdueCampaigns();
                return new DashboardTodosResponseDto(overdueCampaigns);
        }

        /** §8.2 GET /dashboard/heat-sources */
        public DashboardHeatSourcesResponseDto getHeatSources() {
                List<HeatSourceDto> items = heatSourceRepository.findAll().stream()
                                .map(hs -> new HeatSourceDto(
                                                hs.getSourceCode().name(),
                                                hs.getAvailability().name(),
                                                hs.isEnabled(),
                                                toDisplayTime(hs.getLastFetchedAt()),
                                                hs.getQuotaUsed(),
                                                hs.getQuotaLimit()))
                                .toList();

                return new DashboardHeatSourcesResponseDto(items);
        }

        // Shared helper methods

        private List<RankingItemDto> toRankingItems(
                        List<ProductScore> scores,
                        Map<Long, SceneClassificationLog> latestLogs,
                        Map<Long, Severity> riskMap) {
                return scores.stream()
                                .map(score -> toRankingItemDto(score, latestLogs, riskMap))
                                .toList();
        }

        /**
         * FR-02：排行欄位含情境判定標籤與人工覆寫標記。
         * 以該品項最新一筆判定紀錄為準 —— 必須是「覆寫且覆寫結果等於本榜情境」才標記，
         * 只查「曾經覆寫過」會把後來重新 AI 判定的品項誤標。
         */
        private RankingItemDto toRankingItemDto(
                        ProductScore score,
                        Map<Long, SceneClassificationLog> latestLogs,
                        Map<Long, Severity> riskMap) {
                SceneClassificationLog log = latestLogs.get(score.getProduct().getId());
                boolean overridden = log != null
                                && log.isOverridden()
                                && log.getFinalSceneType() == score.getSceneType();

                Severity severity = riskMap.get(score.getProduct().getId());
                String riskLevel = severity == null ? "NONE" : severity.name();

                return new RankingItemDto(
                                score.getProduct().getId(),
                                score.getProduct().getName(),
                                score.getFinalScore(),
                                score.getGrade().name(),
                                score.getSceneType().name(),
                                overridden,
                                riskLevel);
        }

        private Map<Long, SceneClassificationLog> loadLatestClassificationLogs(
                        List<List<ProductScore>> boards) {
                Set<Long> productIds = new HashSet<>();
                for (List<ProductScore> board : boards) {
                        board.forEach(s -> productIds.add(s.getProduct().getId()));
                }
                if (productIds.isEmpty()) {
                        return Map.of();
                }

                Map<Long, SceneClassificationLog> latest = new HashMap<>();
                sceneClassificationLogRepository.findLatestByProductIds(productIds)
                                .forEach(log -> latest.putIfAbsent(log.getProduct().getId(), log));
                return latest;
        }

        private List<OverdueCampaignDto> getOverdueCampaigns() {
                LocalDate cutoff = LocalDate.now().minusDays(7);
                List<DecisionRecord> overdue = decisionRecordRepository.findOverdueCampaigns(
                                DecisionType.ADOPT,
                                cutoff);
                return overdue.stream()
                                .map(dr -> new OverdueCampaignDto(
                                                dr.getProduct().getId(),
                                                dr.getProduct().getName(),
                                                dr.getCampaignEndDate(),
                                                ChronoUnit.DAYS.between(dr.getCampaignEndDate(), LocalDate.now()) - 7))
                                .toList();
        }

        private static OffsetDateTime toDisplayTime(Instant instant) {
                return instant == null
                                ? null
                                : instant.atZone(DISPLAY_ZONE).toOffsetDateTime();
        }
}