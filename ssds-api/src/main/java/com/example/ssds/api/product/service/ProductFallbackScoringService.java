package com.example.ssds.api.product.service;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.LogisticsCondition;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.Season;
import com.example.ssds.infra.dao.ProductMarginStatisticsDao;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * LLM/外部因子尚未供應時的確定性評分路徑。
 * 只採用真實可得的毛利率百分位；其餘加分因子標示無資料並降低信心度，
 * 不以假資料填值。物流與庫存扣分則依品項主檔直接計算。
 */
@Service
public class ProductFallbackScoringService {

    private static final SceneType SCENE = SceneType.REPLENISHMENT;
    private static final Set<FactorCode> BONUS_FACTORS = EnumSet.of(
            FactorCode.TREND, FactorCode.MARGIN, FactorCode.CVR,
            FactorCode.PRICE_FIT, FactorCode.FESTIVAL, FactorCode.CLIMATE
    );

    private final WeightVersionRepository weightVersionRepository;
    private final ProductScoreRepository scoreRepository;
    private final ProductMarginStatisticsDao marginStatisticsDao;

    public ProductFallbackScoringService(
            WeightVersionRepository weightVersionRepository,
            ProductScoreRepository scoreRepository,
            ProductMarginStatisticsDao marginStatisticsDao
    ) {
        this.weightVersionRepository = weightVersionRepository;
        this.scoreRepository = scoreRepository;
        this.marginStatisticsDao = marginStatisticsDao;
    }

    public ProductScore score(Product product) {
        WeightVersion version = weightVersionRepository.findByIsCurrentTrue()
                .orElseThrow(() -> new IllegalStateException("目前沒有生效中的權重版本"));
        var margin = marginStatisticsDao.findPercentile(product.getId(), product.getCategory().getId())
                .orElseThrow(() -> new InsufficientDataException("品項沒有可用的毛利率，無法評分"));

        BigDecimal bonus = margin.normalizedValue().setScale(2, RoundingMode.HALF_UP);
        BigDecimal logisticsPenalty = logisticsPenalty(product);
        BigDecimal inventoryPenalty = inventoryPenalty(product);
        BigDecimal penalty = logisticsPenalty.add(inventoryPenalty)
                .min(BigDecimal.valueOf(FactorCode.PENALTY_CAP));
        BigDecimal finalScore = bonus.subtract(penalty).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        var threshold = marginStatisticsDao.findGradeThreshold(version.getId(), SCENE.name())
                .orElse(new ProductMarginStatisticsDao.GradeThreshold(
                        BigDecimal.valueOf(80), BigDecimal.valueOf(65)
                ));
        Grade grade = grade(finalScore, penalty, threshold);
        int confidence = Math.max(0, 100 - 5 * 8 - (margin.imputed() ? 24 : 0));
        String period = isoWeek(LocalDate.now(ZoneId.of("Asia/Taipei")));

        scoreRepository.deactivateCurrent(product.getId(), period, SCENE);
        ProductScore score = ProductScore.builder()
                .product(product)
                .weightVersion(version)
                .period(period)
                .sceneType(SCENE)
                .primary(true)
                .active(true)
                .bonusSubtotal(bonus)
                .penaltySubtotal(penalty)
                .finalScore(finalScore)
                .grade(grade)
                .confidence(confidence)
                .build();

        List<ScoreFactor> factors = new ArrayList<>();
        for (FactorCode code : BONUS_FACTORS) {
            boolean available = code == FactorCode.MARGIN;
            factors.add(ScoreFactor.builder()
                    .score(score)
                    .factorCode(code)
                    .rawValue(available ? product.getMarginRate() : null)
                    .normalizedValue(available ? bonus : null)
                    .weight(available ? BigDecimal.ONE.setScale(3) : BigDecimal.ZERO.setScale(3))
                    .imputed(available && margin.imputed())
                    .penalty(false)
                    .dataAvailable(available)
                    .build());
        }
        factors.add(penaltyFactor(score, FactorCode.REVIEW_RISK, BigDecimal.ZERO, false));
        factors.add(penaltyFactor(score, FactorCode.LOGISTICS_RISK, logisticsPenalty,
                product.getLogisticsCondition() != null && !product.getLogisticsCondition().isBlank()));
        factors.add(penaltyFactor(score, FactorCode.INVENTORY_RISK, inventoryPenalty,
                product.getMoq() != null || product.getShelfLifeDays() != null
                        || product.getSeason() != Season.ALL));
        score.setFactors(factors);
        return scoreRepository.saveAndFlush(score);
    }

    private ScoreFactor penaltyFactor(
            ProductScore score, FactorCode code, BigDecimal value, boolean available
    ) {
        return ScoreFactor.builder()
                .score(score)
                .factorCode(code)
                .penalty(true)
                .penaltyValue(value.setScale(1, RoundingMode.HALF_UP))
                .dataAvailable(available)
                .build();
    }

    private BigDecimal logisticsPenalty(Product product) {
        Set<LogisticsCondition> conditions = ProductLogisticsConditionMapper.decode(
                product.getLogisticsCondition()
        );
        int points = 0;
        if (conditions.contains(LogisticsCondition.FROZEN)) points += 4;
        else if (conditions.contains(LogisticsCondition.CHILLED)) points += 3;
        if (conditions.contains(LogisticsCondition.MELTABLE)) points += 3;
        if (conditions.contains(LogisticsCondition.FRAGILE)) points += 2;
        if (conditions.contains(LogisticsCondition.OVERSIZED)) points += 2;
        return BigDecimal.valueOf(Math.min(points, FactorCode.LOGISTICS_RISK.maxPenalty()));
    }

    private BigDecimal inventoryPenalty(Product product) {
        int points = 0;
        if (product.getShelfLifeDays() != null) {
            if (product.getShelfLifeDays() < 30) points += 5;
            else if (product.getShelfLifeDays() < 60) points += 3;
        }
        if (product.getMoq() != null) {
            if (product.getMoq() >= 1000) points += 4;
            else if (product.getMoq() >= 500) points += 2;
        }
        if (product.getSeason() != null && product.getSeason() != Season.ALL) points += 2;
        return BigDecimal.valueOf(Math.min(points, FactorCode.INVENTORY_RISK.maxPenalty()));
    }

    private Grade grade(
            BigDecimal score,
            BigDecimal penalty,
            ProductMarginStatisticsDao.GradeThreshold threshold
    ) {
        Grade result = score.compareTo(threshold.gradeAMin()) >= 0
                ? Grade.A
                : score.compareTo(threshold.gradeBMin()) >= 0 ? Grade.B : Grade.C;
        return penalty.compareTo(BigDecimal.valueOf(FactorCode.PENALTY_GRADE_SUPPRESS_THRESHOLD)) >= 0
                && result == Grade.A ? Grade.B : result;
    }

    private String isoWeek(LocalDate date) {
        WeekFields fields = WeekFields.ISO;
        return "%04dW%02d".formatted(
                date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear())
        );
    }
}
