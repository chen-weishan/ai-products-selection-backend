package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.repository.ProductScoreRepository;

/** 為一批示警產生 S-11「影響」欄文字；扣分類示警才需要查最新評分，且一批只查一次。 */
@Service
public class RiskImpactService {

    private static final Set<String> PENALTY_TYPES =
            Set.of(RiskTypes.REVIEW_RISK, RiskTypes.LOGISTICS_RISK, RiskTypes.INVENTORY_RISK);

    private final ProductScoreRepository scores;

    public RiskImpactService(ProductScoreRepository scores) {
        this.scores = scores;
    }

    /** @return 示警 id → 影響文字 */
    @Transactional(readOnly = true)
    public Map<Long, String> describe(Collection<RiskAlert> alerts) {
        Set<Long> penaltyProductIds = alerts.stream()
                .filter(alert -> PENALTY_TYPES.contains(alert.getRiskType()))
                .map(alert -> alert.getProduct().getId())
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, ProductScore> latest = new HashMap<>();
        if (!penaltyProductIds.isEmpty()) {
            for (ProductScore score : scores.findLatestPrimaryActiveWithFactors(penaltyProductIds)) {
                latest.put(score.getProduct().getId(), score);
            }
        }
        Map<Long, String> result = new HashMap<>();
        for (RiskAlert alert : alerts) {
            ProductScore score = latest.get(alert.getProduct().getId());
            result.put(alert.getId(), RiskImpactDescriber.describe(
                    alert.getRiskType(),
                    alert.getProduct().getStatus(),
                    factorPenalty(score, alert.getRiskType()),
                    score == null ? null : score.getPenaltySubtotal()));
        }
        return result;
    }

    private static BigDecimal factorPenalty(ProductScore score, String riskType) {
        if (score == null || !PENALTY_TYPES.contains(riskType)) {
            return null;
        }
        FactorCode code = FactorCode.valueOf(riskType);
        return score.getFactors().stream()
                .filter(factor -> factor.getFactorCode() == code)
                .map(ScoreFactor::getPenaltyValue)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
