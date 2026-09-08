package com.example.ssds.api.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.api.score.dto.SimulateRequest;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;

import lombok.RequiredArgsConstructor;

/**
 * FR-04 以其他權重版本試算（規格書 §FR-04、AC-04-4、§8.2 POST /scores/simulate）。
 *
 * <p><b>本服務絕對不寫入資料庫</b>（AC-04-4）。做法不是靠 {@code readOnly = true}
 * 去攔，而是重算結果全部放進區域變數與 DTO，從頭到尾沒有任何 entity 的 setter 被呼叫。
 * {@code readOnly} 只是第二道保險。
 *
 * <p>與 {@code ScoreQueryService} 分開：那支的職責是「查出來換形狀」，
 * 這支是「重算」。混在一起會讓人分不清哪些方法帶計算邏輯。
 *
 * <p>不需要重做百分位正規化：{@code score_factor.normalized_value} 是各因子
 * 對同品類的百分位，與權重無關（§5.3.1）。換權重版本只影響加權，不影響正規化值。
 */
@Service
@RequiredArgsConstructor
public class ScoreSimulationService {

    private final ProductScoreRepository productScoreRepository;
    private final ScoreFactorRepository scoreFactorRepository;
    private final WeightVersionRepository weightVersionRepository;
    private final GradeThresholdRepository gradeThresholdRepository;

    /** §5.6 硬規則的門檻：扣分達此值（含）以上，分級最高只給 B。 */
    private static final BigDecimal PENALTY_CAP = BigDecimal.valueOf(20);

    /** 分數值域上限（§5.5 加分小計上限 100）。 */
    private static final BigDecimal GRADE_MAX = BigDecimal.valueOf(100);

    /** §5.7 分攤的中間精度。最終分數另外收斂到 2 位（DECIMAL(5,2)）。 */
    private static final int WEIGHT_SCALE = 6;

    /** §5.7：六個加分因子缺 4 項以上（過半）就不產生分數，因此至少要有 3 項有資料。 */
    private static final int MIN_AVAILABLE_FACTORS = 3;

    private static final int DEFAULT_LIMIT = 20;

    @Transactional(readOnly = true)
    public List<ScoreRankingRowResponse> simulate(SimulateRequest request) {

        // 1. 取權重版本（帶 profiles）
        WeightVersion version = weightVersionRepository
                .findWithProfilesById(request.weightVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到權重版本 id=" + request.weightVersionId()));

        // 2. 取該版本的四榜門檻，轉成可查表的形式
        Map<SceneType, GradeThreshold> thresholds =
                gradeThresholdRepository.findByVersionId(request.weightVersionId()).stream()
                        .collect(Collectors.toMap(GradeThreshold::getSceneType, t -> t,
                                (a, b) -> a, () -> new EnumMap<>(SceneType.class)));

        // 3. 版本權重為底，overrides 覆寫；4. 驗證每組加總 == 1.000
        Map<SceneType, Map<FactorCode, BigDecimal>> weights = resolveWeights(version, request);
        validateWeights(weights);
        validateThresholdOverrides(request);

        // 5. 取要重算的母體。與正式排行共用同一支查詢，過濾條件（is_active、軟刪除）一致
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        List<ProductScore> scores = productScoreRepository.findRanking(
                request.period(), request.scene(), request.categoryId(),
                PageRequest.of(0, limit)).getContent();

        if (scores.isEmpty()) {
            return List.of();
        }

        // 6. 一次撈完全部因子再分組，避免 N+1
        Map<Long, List<ScoreFactor>> factorsByScoreId = scoreFactorRepository
                .findByScoreIdIn(scores.stream().map(ProductScore::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(f -> f.getScore().getId()));

        // 7. 逐筆重算
        List<ScoreRankingRowResponse> rows = new ArrayList<>();
        for (ProductScore score : scores) {
            List<ScoreFactor> factors = factorsByScoreId.getOrDefault(score.getId(), List.of());
            Simulated simulated = recompute(score, factors, weights, thresholds, request);
            // 資料不足（§5.7）→ 不產生分數，整筆不列入結果
            if (simulated == null) {
                continue;
            }
            rows.add(ScoreMapper.toSimulatedRow(score, factors, simulated.effectiveWeights(),
                    simulated.bonusSubtotal(), simulated.finalScore(), simulated.grade()));
        }

        // 8. 分數變了，資料庫排的順序已失效，必須在 Java 端重排
        rows.sort(Comparator.comparing(ScoreRankingRowResponse::finalScore).reversed()
                .thenComparing(ScoreRankingRowResponse::scoreId));
        return rows;
    }

    /**
     * 版本自身的四組權重為底，{@code overrides} 有的那一榜整組換掉。
     *
     * <p>覆寫是「整組取代」而不是「逐因子合併」：§8.2 的請求範例一次給滿六個因子，
     * 而且部分合併會產生加總不為 1.000 的組合，語意上說不清楚。
     */
    private Map<SceneType, Map<FactorCode, BigDecimal>> resolveWeights(
            WeightVersion version, SimulateRequest request) {

        Map<SceneType, Map<FactorCode, BigDecimal>> result = new EnumMap<>(SceneType.class);

        for (WeightProfile profile : version.getProfiles()) {
            result.computeIfAbsent(profile.getSceneType(),
                    s -> new EnumMap<>(FactorCode.class))
                    .put(profile.getFactorCode(), profile.getWeight());
        }

        if (request.overrides() != null) {
            request.overrides().forEach((scene, factorWeights) ->
                    result.put(scene, new EnumMap<>(factorWeights)));
        }
        return result;
    }

    /**
     * 驗證合併後的權重：先檢查單值域 0–1，再檢查每榜加總 = 1.000。
     *
     * <p><b>順序不能反</b>：負權重會被超過 1 的權重抵銷，只驗加總會讓
     * {@code (-0.5, 1.5)} 這種組合通過，算出來的分數毫無意義。
     * 規則與 {@code WeightVersionCommandService.validateRequest()} 一致，
     * 試算路徑同樣要擋。
     */
    private void validateWeights(Map<SceneType, Map<FactorCode, BigDecimal>> weights) {
        for (Map.Entry<SceneType, Map<FactorCode, BigDecimal>> entry : weights.entrySet()) {
            SceneType scene = entry.getKey();

            for (Map.Entry<FactorCode, BigDecimal> w : entry.getValue().entrySet()) {
                BigDecimal value = w.getValue();
                if (value == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            scene + " 榜的 " + w.getKey() + " 權重不可為空");
                }
                if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            scene + " 榜的 " + w.getKey() + " 權重為 " + value
                                    + "，必須介於 0.000 與 1.000 之間");
                }
            }

            BigDecimal sum = entry.getValue().values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessException(ErrorCode.WEIGHT_SUM_INVALID,
                        scene + " 榜權重加總為 " + sum + "，必須等於 1.000");
            }
        }
    }

    /**
     * 驗證 {@code thresholdOverrides}：值域 0–100，且 A 級門檻必須大於 B 級。
     *
     * <p>規則與 FR-08 的 {@code grade_threshold} 一致（DB 端為
     * {@code ck_grade_threshold_range} 與 {@code ck_grade_threshold_order}）。
     * 試算不寫資料庫、碰不到那兩個 CHECK，所以必須在這裡自己擋——
     * 否則 A 門檻小於 B 門檻時分級規則無解，會安靜地算出錯誤的分級。
     */
    private void validateThresholdOverrides(SimulateRequest request) {
        if (request.thresholdOverrides() == null) {
            return;
        }
        request.thresholdOverrides().forEach((scene, override) -> {
            if (override == null || override.gradeAMin() == null || override.gradeBMin() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        scene + " 榜的門檻覆寫必須同時提供 gradeAMin 與 gradeBMin");
            }
            validateGradeRange(scene, "A 級門檻", override.gradeAMin());
            validateGradeRange(scene, "B 級門檻", override.gradeBMin());
            if (override.gradeAMin().compareTo(override.gradeBMin()) <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        scene + " 榜的 A 級門檻（" + override.gradeAMin()
                                + "）必須大於 B 級門檻（" + override.gradeBMin() + "）");
            }
        });
    }

    /** 分數值域 0–100（§5.5 加分小計上限 100）。 */
    private void validateGradeRange(SceneType scene, String label, BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(GRADE_MAX) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    scene + " 榜的 " + label + "為 " + value + "，必須介於 0 與 100 之間");
        }
    }

    /**
     * 單筆重算：§5.7 分攤 → §5.5 加權 → §5.6 分級 → PENALTY_CAP。
     *
     * <p><b>全程不碰 entity 的 setter。</b>算出來的值放進 {@link Simulated} 回傳，
     * 交由 Mapper 組成 DTO。改 entity 會被 dirty checking 寫回資料庫，違反 AC-04-4。
     *
     * @return 資料不足以評分時回 {@code null}（§5.7：加分因子缺 4 項以上不產生分數）
     */
    private Simulated recompute(
            ProductScore score,
            List<ScoreFactor> factors,
            Map<SceneType, Map<FactorCode, BigDecimal>> weightsByScene,
            Map<SceneType, GradeThreshold> thresholds,
            SimulateRequest request) {

        Map<FactorCode, BigDecimal> weights =
                weightsByScene.getOrDefault(score.getSceneType(), Map.of());

        // 只有「有資料」的加分因子參與加權。判斷一律看 dataAvailable：
        // 無資料的因子 weight 可能是 null 也可能是 0，用 weight == null 會漏判
        List<ScoreFactor> available = factors.stream()
                .filter(f -> !f.isPenalty())
                .filter(ScoreFactor::isDataAvailable)
                .filter(f -> f.getNormalizedValue() != null)
                .filter(f -> weights.get(f.getFactorCode()) != null)
                .toList();

        if (available.size() < MIN_AVAILABLE_FACTORS) {
            return null;
        }

        // §5.7 分攤：w'_i = w_i / Σ(w_k for k in available)
        BigDecimal denom = available.stream()
                .map(f -> weights.get(f.getFactorCode()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (denom.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        Map<FactorCode, BigDecimal> effectiveWeights = new EnumMap<>(FactorCode.class);
        BigDecimal bonus = BigDecimal.ZERO;
        for (ScoreFactor factor : available) {
            // divide 必須給 scale 與 RoundingMode，否則除不盡會拋 ArithmeticException
            BigDecimal effective = weights.get(factor.getFactorCode())
                    .divide(denom, WEIGHT_SCALE, RoundingMode.HALF_UP);
            effectiveWeights.put(factor.getFactorCode(), effective);
            bonus = bonus.add(effective.multiply(factor.getNormalizedValue()));
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        // 扣分不重算：扣分因子固定生效、不參與權重調整（§5.2.2），換權重版本不影響它
        BigDecimal penalty = score.getPenaltySubtotal() == null
                ? BigDecimal.ZERO
                : score.getPenaltySubtotal();

        BigDecimal finalScore = bonus.subtract(penalty).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        Grade grade = grade(finalScore, score.getSceneType(), thresholds, request);

        // §5.6 硬規則：扣分 >= 20 者分級最高只給 B。必須在分級「之後」壓
        if (grade == Grade.A && penalty.compareTo(PENALTY_CAP) >= 0) {
            grade = Grade.B;
        }

        return new Simulated(bonus, finalScore, grade, effectiveWeights);
    }

    /** 依該榜門檻分級。{@code thresholdOverrides} 有這一榜時優先採用。 */
    private Grade grade(
            BigDecimal finalScore,
            SceneType scene,
            Map<SceneType, GradeThreshold> thresholds,
            SimulateRequest request) {

        BigDecimal aMin;
        BigDecimal bMin;

        SimulateRequest.ThresholdOverride override =
                request.thresholdOverrides() == null ? null : request.thresholdOverrides().get(scene);

        if (override != null) {
            aMin = override.gradeAMin();
            bMin = override.gradeBMin();
        } else {
            GradeThreshold threshold = thresholds.get(scene);
            if (threshold == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "權重版本缺少 " + scene + " 榜的分級門檻");
            }
            aMin = threshold.getGradeAMin();
            bMin = threshold.getGradeBMin();
        }

        if (finalScore.compareTo(aMin) >= 0) {
            return Grade.A;
        }
        if (finalScore.compareTo(bMin) >= 0) {
            return Grade.B;
        }
        return Grade.C;
    }

    /**
     * 單筆重算的結果。只在本服務內部流動，不對外曝光。
     *
     * @param effectiveWeights §5.7 分攤後的權重，供畫面長條顯示「這次試算實際用的權重」
     */
    private record Simulated(
            BigDecimal bonusSubtotal,
            BigDecimal finalScore,
            Grade grade,
            Map<FactorCode, BigDecimal> effectiveWeights) {
    }
}
