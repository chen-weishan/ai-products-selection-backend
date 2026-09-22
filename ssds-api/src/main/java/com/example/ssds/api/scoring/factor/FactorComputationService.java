package com.example.ssds.api.scoring.factor;

import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.factor.CvrFactorProvider.CvrEvidence;
import com.example.ssds.api.scoring.factor.FestivalFactorProvider.FestivalAffinity;
import com.example.ssds.api.scoring.factor.InventoryRiskFactorProvider.InventoryRule;
import com.example.ssds.api.scoring.factor.LogisticsRiskFactorProvider.LogisticsRule;
import com.example.ssds.api.scoring.factor.PriceFitFactorProvider.AudienceBand;
import com.example.ssds.api.scoring.factor.ReviewRiskFactorProvider.ReviewEvidence;
import com.example.ssds.api.scoring.factor.TrendFactorProvider.KeywordTrend;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LogisticsCondition;
import com.example.ssds.core.domain.Season;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 九因子純計算入口。呼叫端先完成同批次 raw evidence 與百分位母體準備，本服務不讀資料庫、
 * 不呼叫 LLM，因此相同 evidence 必定產生相同結果。
 */
@Service
public class FactorComputationService {
    private final TrendFactorProvider trend = new TrendFactorProvider();
    private final MarginFactorProvider margin = new MarginFactorProvider();
    private final CvrFactorProvider cvr = new CvrFactorProvider();
    private final PriceFitFactorProvider priceFit = new PriceFitFactorProvider();
    private final FestivalFactorProvider festival = new FestivalFactorProvider();
    private final ClimateFactorProvider climate = new ClimateFactorProvider();
    private final ReviewRiskFactorProvider reviewRisk = new ReviewRiskFactorProvider();
    private final LogisticsRiskFactorProvider logisticsRisk = new LogisticsRiskFactorProvider();
    private final InventoryRiskFactorProvider inventoryRisk = new InventoryRiskFactorProvider();

    public Map<FactorCode, FactorInput> compute(Evidence evidence) {
        return FactorInputSet.from(List.of(
                trend.provide(evidence.keywordTrends(), evidence.trendBasis()),
                margin.provide(evidence.marginRate(), evidence.marginBasis()),
                cvr.provide(evidence.cvrEvidence(), evidence.cvrBasis()),
                priceFit.provide(evidence.suggestedPrice(), evidence.audienceMix(), evidence.priceFitBasis()),
                festival.provide(
                        evidence.evaluationDate(),
                        evidence.leadTimeDays(),
                        evidence.festivalAffinities(),
                        evidence.festivalBasis()),
                climate.provide(evidence.climateEvidence(), evidence.climateBasis()),
                reviewRisk.provide(evidence.reviewEvidence()),
                logisticsRisk.provide(
                        evidence.logisticsConditions(), evidence.evaluationMonth(), evidence.logisticsRule()),
                inventoryRisk.provide(
                        evidence.shelfLifeDays(), evidence.season(), evidence.moq(), evidence.inventoryRule())));
    }

    /**
     * 一次評分的不可變 evidence。百分位 basis 必須來自同一批次完成的 raw values，
     * 不得在逐品項處理途中邊寫邊查。
     */
    public record Evidence(
            List<KeywordTrend> keywordTrends,
            PercentileBasis trendBasis,
            BigDecimal marginRate,
            PercentileBasis marginBasis,
            CvrEvidence cvrEvidence,
            PercentileBasis cvrBasis,
            BigDecimal suggestedPrice,
            List<AudienceBand> audienceMix,
            PercentileBasis priceFitBasis,
            LocalDate evaluationDate,
            int leadTimeDays,
            List<FestivalAffinity> festivalAffinities,
            PercentileBasis festivalBasis,
            ClimateFactorProvider.ClimateEvidence climateEvidence,
            PercentileBasis climateBasis,
            ReviewEvidence reviewEvidence,
            Set<LogisticsCondition> logisticsConditions,
            Month evaluationMonth,
            LogisticsRule logisticsRule,
            Integer shelfLifeDays,
            Season season,
            Integer moq,
            InventoryRule inventoryRule) {

        public Evidence withBases(Map<FactorCode, PercentileBasis> bases) {
            return new Evidence(
                    keywordTrends, bases.get(FactorCode.TREND),
                    marginRate, bases.get(FactorCode.MARGIN),
                    cvrEvidence, bases.get(FactorCode.CVR),
                    suggestedPrice, audienceMix, bases.get(FactorCode.PRICE_FIT),
                    evaluationDate, leadTimeDays, festivalAffinities, bases.get(FactorCode.FESTIVAL),
                    climateEvidence, bases.get(FactorCode.CLIMATE),
                    reviewEvidence, logisticsConditions, evaluationMonth, logisticsRule,
                    shelfLifeDays, season, moq, inventoryRule);
        }
    }
}
