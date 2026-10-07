package com.example.ssds.api.calibration;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.calibration.CalibrationSample;
import com.example.ssds.calibration.WeightScheme;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.CampaignResult;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * 把資料庫的決策、評分快照與權重版本轉成 {@code ssds-calibration} 的純資料輸入。
 *
 * <p>樣本 = 截止點前已回填 {@code campaign_result} 的決策（品項未刪除），
 * 因子值取決策綁定的那筆評分（AC-11-6：權重改版不回頭改動它）。
 * 樣本是累積的、不限當季：校準要的是「至今所有帶結果標記的開團」（§FR-15 200 筆的算術）。
 */
@Component
@RequiredArgsConstructor
class CalibrationDataLoader {

    private static final Set<SelloutStatus> HIT = Set.of(SelloutStatus.EARLY_SELLOUT, SelloutStatus.ON_TIME);

    private final DecisionRecordRepository decisionRecordRepository;
    private final ScoreFactorRepository scoreFactorRepository;
    private final WeightVersionRepository weightVersionRepository;
    private final GradeThresholdRepository gradeThresholdRepository;
    private final EntityManager entityManager;

    List<CalibrationSample> samples(Instant cutoff) {
        Specification<DecisionRecord> spec = (root, query, cb) -> cb.and(
                cb.isNull(root.get("product").get("deletedAt")),
                cb.lessThan(root.join("result").get("filledAt"), cutoff));
        List<DecisionRecord> decisions = decisionRecordRepository.findAll(spec);

        List<Long> scoreIds = decisions.stream().map(d -> d.getScore().getId()).toList();
        Map<Long, List<ScoreFactor>> factorsByScore = scoreIds.isEmpty()
                ? Map.of()
                : scoreFactorRepository.findByScoreIdIn(scoreIds).stream()
                        .collect(Collectors.groupingBy(f -> f.getScore().getId()));

        return decisions.stream()
                .map(d -> toSample(d, factorsByScore.getOrDefault(d.getScore().getId(), List.of())))
                .toList();
    }

    private static CalibrationSample toSample(DecisionRecord decision, List<ScoreFactor> factors) {
        Map<FactorCode, Double> values = new EnumMap<>(FactorCode.class);
        for (ScoreFactor factor : factors) {
            if (!factor.isPenalty() && factor.isDataAvailable() && factor.getNormalizedValue() != null) {
                values.put(factor.getFactorCode(), factor.getNormalizedValue().doubleValue());
            }
        }
        CampaignResult result = decision.getResult();
        return new CalibrationSample(
                decision.getId(),
                decision.getScore().getSceneType(),
                values,
                decision.getScore().getPenaltySubtotal().doubleValue(),
                result.getActualQty(),
                HIT.contains(result.getSelloutStatus()));
    }

    /** 樣本中最早的回填時間（S-19 顯示樣本期間）；尚無樣本時為 null。條件與 {@link #samples} 相同。 */
    Instant earliestFilledAt(Instant cutoff) {
        return entityManager.createQuery(
                        "select min(r.filledAt) from CampaignResult r"
                                + " where r.filledAt < :cutoff and r.decision.product.deletedAt is null",
                        Instant.class)
                .setParameter("cutoff", cutoff)
                .getSingleResult();
    }

    /** 只取 id：判斷報告的基準版本是否已被取代，不需要載入權重。 */
    Optional<Long> currentVersionId() {
        return entityManager.createQuery("select v.id from WeightVersion v where v.isCurrent = true", Long.class)
                .getResultStream().findFirst();
    }

    WeightVersion currentVersion() {
        return weightVersionRepository.findByIsCurrentTrue()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "目前沒有生效中的權重版本，無法進行校準"));
    }

    WeightVersion version(Long id) {
        return weightVersionRepository.findWithProfilesById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到權重版本 id=" + id));
    }

    /** 四榜 × 六因子權重；版本缺任何一榜即無法回測或校準。 */
    static Map<SceneType, Map<FactorCode, BigDecimal>> weightsOf(WeightVersion version) {
        Map<SceneType, Map<FactorCode, BigDecimal>> weights = new EnumMap<>(SceneType.class);
        for (WeightProfile profile : version.getProfiles()) {
            weights.computeIfAbsent(profile.getSceneType(), s -> new EnumMap<>(FactorCode.class))
                    .put(profile.getFactorCode(), profile.getWeight());
        }
        for (SceneType scene : SceneType.values()) {
            if (!weights.containsKey(scene)) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "權重版本 " + version.getVersionNo() + " 缺少 " + scene + " 榜的權重");
            }
        }
        return weights;
    }

    Map<SceneType, WeightScheme.GradeCut> thresholdsOf(WeightVersion version) {
        List<GradeThreshold> rows = gradeThresholdRepository.findByVersionId(version.getId());
        Map<SceneType, WeightScheme.GradeCut> cuts = new EnumMap<>(SceneType.class);
        for (GradeThreshold row : rows) {
            cuts.put(row.getSceneType(),
                    new WeightScheme.GradeCut(row.getGradeAMin().doubleValue(), row.getGradeBMin().doubleValue()));
        }
        for (SceneType scene : SceneType.values()) {
            if (!cuts.containsKey(scene)) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "權重版本 " + version.getVersionNo() + " 缺少 " + scene + " 榜的分級門檻");
            }
        }
        return cuts;
    }

    List<GradeThreshold> thresholdRows(Long versionId) {
        return gradeThresholdRepository.findByVersionId(versionId);
    }

    WeightScheme schemeOf(String code, WeightVersion version) {
        return new WeightScheme(code, version.getVersionNo() + "（" + version.getName() + "）", version.getId(),
                toDouble(weightsOf(version)), thresholdsOf(version));
    }

    static Map<SceneType, Map<FactorCode, Double>> toDouble(Map<SceneType, Map<FactorCode, BigDecimal>> weights) {
        Map<SceneType, Map<FactorCode, Double>> result = new EnumMap<>(SceneType.class);
        weights.forEach((scene, factors) -> {
            Map<FactorCode, Double> converted = new EnumMap<>(FactorCode.class);
            factors.forEach((code, weight) -> converted.put(code, weight.doubleValue()));
            result.put(scene, converted);
        });
        return result;
    }
}
