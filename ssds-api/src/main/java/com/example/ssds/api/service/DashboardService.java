package com.example.ssds.api.service;

import com.example.ssds.api.dto.DashboardSummaryDto;
import com.example.ssds.api.dto.HeatSourceDto;
import com.example.ssds.api.dto.KpiDto;
import com.example.ssds.api.dto.RankingItemDto;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

        private final ProductRepository productRepository;
        private final ProductScoreRepository productScoreRepository;
        private final RiskAlertRepository riskAlertRepository;
        private final HeatSourceRepository heatSourceRepository;

        public DashboardSummaryDto getSummary(String period) {
                KpiDto kpi = new KpiDto(
                                productRepository.count(),
                                productScoreRepository.countAGradeByPeriod(period),
                                riskAlertRepository.countByStatus(AlertStatus.OPEN),
                                0L);

                Pageable topFive = PageRequest.of(0, 5);

                List<RankingItemDto> viralRanking = productScoreRepository
                                .findTopByPeriodAndSceneType(period, "VIRAL", topFive)
                                .stream()
                                .map(this::toRankingItemDto)
                                .toList();

                List<RankingItemDto> festivalRanking = productScoreRepository
                                .findTopByPeriodAndSceneType(period, "FESTIVAL", topFive)
                                .stream()
                                .map(this::toRankingItemDto)
                                .toList();

                List<RankingItemDto> restockRanking = productScoreRepository
                                .findTopByPeriodAndSceneType(period, "REPLENISHMENT", topFive)
                                .stream()
                                .map(this::toRankingItemDto)
                                .toList();

                List<RankingItemDto> seasonalRanking = productScoreRepository
                                .findTopByPeriodAndSceneType(period, "SEASONAL", topFive)
                                .stream()
                                .map(this::toRankingItemDto)
                                .toList();

                List<HeatSourceDto> heatSources = heatSourceRepository.findAll().stream()
                                .map(hs -> new HeatSourceDto(
                                                hs.getSourceCode().name(),
                                                hs.getAvailability().name(),
                                                hs.isEnabled()))
                                .toList();

                return new DashboardSummaryDto(
                                kpi,
                                viralRanking,
                                festivalRanking,
                                restockRanking,
                                seasonalRanking,
                                heatSources);
        }

        private RankingItemDto toRankingItemDto(ProductScore score) {
                return new RankingItemDto(
                                score.getProduct().getId(),
                                score.getProduct().getName(),
                                score.getFinalScore(),
                                score.getGrade().name(),
                                score.getSceneType().name());
        }
}