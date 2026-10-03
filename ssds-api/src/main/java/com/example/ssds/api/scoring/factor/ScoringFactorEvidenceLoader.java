package com.example.ssds.api.scoring.factor;

import com.example.ssds.api.product.service.ProductLogisticsConditionMapper;
import com.example.ssds.api.scoring.factor.CvrFactorProvider.CvrEvidence;
import com.example.ssds.api.scoring.factor.CvrFactorProvider.SalesSample;
import com.example.ssds.api.scoring.factor.FactorComputationService.Evidence;
import com.example.ssds.api.scoring.factor.FestivalFactorProvider.FestivalAffinity;
import com.example.ssds.api.scoring.factor.PriceFitFactorProvider.AudienceBand;
import com.example.ssds.api.scoring.factor.ReviewRiskFactorProvider.ReviewEvidence;
import com.example.ssds.api.scoring.factor.TrendFactorProvider.KeywordTrend;
import com.example.ssds.infra.dao.ScoringFactorEvidenceDao;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SalesRecord;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.ClimateNormalRepository;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository;
import com.example.ssds.infra.repository.SalesRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 從權威資料表載入單品項 raw evidence；百分位母體由批次服務第二階段補入。 */
@Service
public class ScoringFactorEvidenceLoader {
    private static final LocalDate SALES_HISTORY_START = LocalDate.of(1970, 1, 1);
    private static final BigDecimal DEFAULT_CLIMATE_TOLERANCE = new BigDecimal("12.0");
    private static final PercentileBasis RAW_PASS_BASIS = new PercentileBasis(List.of(), false, null);

    private final HeatCompositeDailyRepository heatRepository;
    private final SalesRecordRepository salesRepository;
    private final ItemFestivalAffinityRepository affinityRepository;
    private final FestivalCalendarRepository festivalRepository;
    private final CategoryLeadTimeRepository leadTimeRepository;
    private final ClimateNormalRepository climateRepository;
    private final ScoringFactorEvidenceDao evidenceDao;
    private final ScoringRiskRuleService ruleService;
    private final String climateRegion;

    public ScoringFactorEvidenceLoader(
            HeatCompositeDailyRepository heatRepository,
            SalesRecordRepository salesRepository,
            ItemFestivalAffinityRepository affinityRepository,
            FestivalCalendarRepository festivalRepository,
            CategoryLeadTimeRepository leadTimeRepository,
            ClimateNormalRepository climateRepository,
            ScoringFactorEvidenceDao evidenceDao,
            ScoringRiskRuleService ruleService,
            @Value("${ssds.scoring.climate-region:TW_NORTH}") String climateRegion) {
        this.heatRepository = heatRepository;
        this.salesRepository = salesRepository;
        this.affinityRepository = affinityRepository;
        this.festivalRepository = festivalRepository;
        this.leadTimeRepository = leadTimeRepository;
        this.climateRepository = climateRepository;
        this.evidenceDao = evidenceDao;
        this.ruleService = ruleService;
        this.climateRegion = climateRegion;
    }

    @Transactional(readOnly = true)
    public Evidence loadRaw(Product product, LocalDate evaluationDate) {
        Long categoryId = product.getCategory().getId();
        var reviewRule = ruleService.reviewRule(categoryId);
        var reviewStats = evidenceDao.findReviewStats(product.getId());
        var climateProfile = evidenceDao.findClimateProfile(categoryId).orElse(null);
        var climateNormal = climateRepository
                .findByRegionCodeAndMonth(climateRegion, (short) evaluationDate.getMonthValue())
                .orElse(null);

        return new Evidence(
                trends(product), RAW_PASS_BASIS,
                product.getMarginRate(), RAW_PASS_BASIS,
                cvr(product, evaluationDate), RAW_PASS_BASIS,
                product.getSuggestedPrice(), audiences(categoryId), RAW_PASS_BASIS,
                evaluationDate,
                leadTimeRepository.findById(categoryId).map(value -> value.getLeadTimeDays()).orElse(0),
                festivals(product), RAW_PASS_BASIS,
                new ClimateFactorProvider.ClimateEvidence(
                        climateNormal == null ? null : climateNormal.getAvgTemp(),
                        product.getIdealTempMin(),
                        product.getIdealTempMax(),
                        climateProfile == null ? null : climateProfile.idealTempMin(),
                        climateProfile == null ? null : climateProfile.idealTempMax(),
                        climateProfile == null ? DEFAULT_CLIMATE_TOLERANCE : climateProfile.tolerance()),
                RAW_PASS_BASIS,
                new ReviewEvidence(
                        reviewStats.totalCount(),
                        reviewStats.negativeCount(),
                        reviewStats.riskTopicNegativeCount(),
                        reviewRule.negativeRateThreshold(),
                        reviewRule.minSampleSize()),
                ProductLogisticsConditionMapper.decode(product.getLogisticsCondition()),
                evaluationDate.getMonth(),
                ruleService.logisticsRule(categoryId),
                product.getShelfLifeDays(),
                product.getSeason(),
                product.getMoq(),
                ruleService.inventoryRule(categoryId));
    }

    private List<KeywordTrend> trends(Product product) {
        Set<Long> keywordIds = product.getKeywords().stream().map(value -> value.getId()).collect(Collectors.toSet());
        if (keywordIds.isEmpty()) return List.of();
        return heatRepository.findLatestEligibleForDrivingKeyword(keywordIds).stream()
                .map(value -> new KeywordTrend(
                        value.getKeyword().getId(),
                        value.getKeyword().getKeyword(),
                        value.getSlope7d(),
                        value.getSlope30d(),
                        value.isVolumeBelowFloor()))
                .toList();
    }

    private CvrEvidence cvr(Product product, LocalDate evaluationDate) {
        List<SalesRecord> own = salesRepository.findByProductIdAndOrderDateBetween(
                product.getId(), SALES_HISTORY_START, evaluationDate);
        List<SalesRecord> category = salesRepository.findByCategoryIdAndOrderDateBetween(
                product.getCategory().getId(), SALES_HISTORY_START, evaluationDate);
        Map<Long, List<SalesRecord>> byProduct = category.stream()
                .filter(value -> value.getProduct() != null)
                .collect(Collectors.groupingBy(value -> value.getProduct().getId()));
        return new CvrEvidence(samples(own), byProduct.values().stream().map(this::samples).toList());
    }

    private List<SalesSample> samples(List<SalesRecord> records) {
        return records.stream().map(value -> new SalesSample(value.getQty(), value.getImpression())).toList();
    }

    private List<AudienceBand> audiences(Long categoryId) {
        return evidenceDao.findAudienceBands(categoryId).stream()
                .map(value -> new AudienceBand(
                        value.audienceCode(), value.priceMin(), value.priceMax(), value.share()))
                .toList();
    }

    private List<FestivalAffinity> festivals(Product product) {
        var affinities = affinityRepository.findByProductId(product.getId());
        if (affinities.isEmpty()) return List.of();
        Map<String, BigDecimal> byCode = affinities.stream().collect(Collectors.toMap(
                value -> value.getFestivalCode(),
                value -> value.getAffinity(),
                (first, ignored) -> first));
        return festivalRepository.findByFestivalCodeIn(byCode.keySet()).stream()
                .map(value -> festival(value, byCode.get(value.getFestivalCode())))
                .toList();
    }

    private FestivalAffinity festival(FestivalCalendar value, BigDecimal affinity) {
        return new FestivalAffinity(
                value.getId(),
                value.getFestivalCode(),
                value.getFestivalName(),
                value.getFestivalDate(),
                affinity);
    }
}
