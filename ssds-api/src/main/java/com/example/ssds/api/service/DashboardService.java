package com.example.ssds.api.service;

import com.example.ssds.api.dto.BtrackSummaryDto;
import com.example.ssds.api.dto.DashboardSummaryDto;
import com.example.ssds.api.dto.HeatSourceDto;
import com.example.ssds.api.dto.KpiDto;
import com.example.ssds.api.dto.OverdueCampaignDto;
import com.example.ssds.api.dto.RankingItemDto;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

        private final ProductRepository productRepository;
        private final ProductScoreRepository productScoreRepository;
        private final RiskAlertRepository riskAlertRepository;
        private final HeatSourceRepository heatSourceRepository;
        private final DecisionRecordRepository decisionRecordRepository;
        private final SourcingCandidateRepository sourcingCandidateRepository;

        public DashboardSummaryDto getSummary(String period, String track) {
                long totalCandidates;
                if ("B".equals(track)) {
                        totalCandidates = productRepository.countByTrackType(TrackType.B);
                } else {
                        totalCandidates = productRepository.count(); // 預設 A 軌
                }

                KpiDto kpi = new KpiDto(
                                totalCandidates,
                                productScoreRepository.countAGradeByPeriod(period),
                                riskAlertRepository.countByStatus(AlertStatus.OPEN),
                                0L);

                Pageable topFive = PageRequest.of(0, 5);

                List<RankingItemDto> viralRanking;
                List<RankingItemDto> festivalRanking;
                List<RankingItemDto> restockRanking;
                List<RankingItemDto> seasonalRanking;

                if ("B".equals(track)) {
                        viralRanking = List.of();
                        festivalRanking = List.of();
                        restockRanking = List.of();
                        seasonalRanking = List.of();
                } else {
                        viralRanking = productScoreRepository
                                        .findTopByPeriodAndSceneType(period, "VIRAL", topFive)
                                        .stream()
                                        .map(this::toRankingItemDto)
                                        .toList();

                        festivalRanking = productScoreRepository
                                        .findTopByPeriodAndSceneType(period, "FESTIVAL", topFive)
                                        .stream()
                                        .map(this::toRankingItemDto)
                                        .toList();

                        restockRanking = productScoreRepository
                                        .findTopByPeriodAndSceneType(period, "REPLENISHMENT", topFive)
                                        .stream()
                                        .map(this::toRankingItemDto)
                                        .toList();

                        seasonalRanking = productScoreRepository
                                        .findTopByPeriodAndSceneType(period, "SEASONAL", topFive)
                                        .stream()
                                        .map(this::toRankingItemDto)
                                        .toList();
                }

                List<HeatSourceDto> heatSources = heatSourceRepository.findAll().stream()
                                .map(hs -> new HeatSourceDto(
                                                hs.getSourceCode().name(),
                                                hs.getAvailability().name(),
                                                hs.isEnabled()))
                                .toList();

                List<OverdueCampaignDto> overdueCampaigns = getOverdueCampaigns();

                // B 軌摘要
                List<BtrackSummaryDto> bTrackSummary = getBTrackSummary();

                return new DashboardSummaryDto(
                                kpi,
                                viralRanking,
                                festivalRanking,
                                restockRanking,
                                seasonalRanking,
                                heatSources,
                                overdueCampaigns,
                                bTrackSummary);
        }

        private RankingItemDto toRankingItemDto(ProductScore score) {
                return new RankingItemDto(
                                score.getProduct().getId(),
                                score.getProduct().getName(),
                                score.getFinalScore(),
                                score.getGrade().name(),
                                score.getSceneType().name());
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
                                .collect(Collectors.toList());
        }

        private List<BtrackSummaryDto> getBTrackSummary() {
                List<SourcingCandidate> candidates = sourcingCandidateRepository
                                .findPriorityListWithLimit(3);
                return candidates.stream()
                                .map(sc -> new BtrackSummaryDto(
                                                sc.getProduct().getId(),
                                                sc.getProduct().getName(),
                                                sc.getHeatStage() != null ? sc.getHeatStage().name() : null,
                                                sc.getTimeGapDays(),
                                                sc.getProduct().getSourcingStatus().name()))
                                .collect(Collectors.toList());
        }
}