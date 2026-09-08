package com.example.ssds.api.scoring;

import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.scoring.ScoringEngine;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 將本次 Agent 1 的適用情境套入現行權重，建立 v3.0 多情境評分快照。 */
@Service
public class ScoreRecalculationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final BigDecimal SCENE_SCORING_CONFIDENCE = new BigDecimal("0.70");

    private final ProductScoreRepository scoreRepository;
    private final WeightVersionRepository weightVersionRepository;
    private final ScoringEngine scoringEngine = new ScoringEngine();

    public ScoreRecalculationService(
            ProductScoreRepository scoreRepository,
            WeightVersionRepository weightVersionRepository) {
        this.scoreRepository = scoreRepository;
        this.weightVersionRepository = weightVersionRepository;
    }

    @Transactional
    public List<ProductScore> recalculate(Long productId, SceneClassificationResponse classification) {
        ProductScore source = scoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(productId)
                .orElseThrow(() -> new IllegalStateException("此品項尚無可供重算的六因子快照"));
        WeightVersion version = weightVersionRepository.findByIsCurrentTrue()
                .orElseThrow(() -> new IllegalStateException("目前沒有生效中的權重版本"));

        SceneType mainScene = classification.sceneType().toDomain();
        LinkedHashSet<SceneType> scenes = new LinkedHashSet<>();
        scenes.add(mainScene);
        if (!classification.fallbackApplied() && classification.alternativeScene() != null) {
            scenes.add(classification.alternativeScene().toDomain());
        }

        Map<FactorCode, ScoreFactor> sourceFactors = source.getFactors().stream()
                .collect(Collectors.toMap(
                        ScoreFactor::getFactorCode,
                        Function.identity(),
                        (first, ignored) -> first,
                        () -> new EnumMap<>(FactorCode.class)));
        Map<FactorCode, ScoringEngine.FactorValue> values = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            ScoreFactor factor = sourceFactors.get(code);
            values.put(code, factor == null
                    ? new ScoringEngine.FactorValue(null, null, false)
                    : new ScoringEngine.FactorValue(
                            factor.getNormalizedValue(), factor.getPenaltyValue(), factor.isDataAvailable()));
        }

        String period = isoWeekPeriod(LocalDate.now(BUSINESS_ZONE));
        Instant calculatedAt = Instant.now();
        List<ProductScore> recalculated = new ArrayList<>();
        for (SceneType scene : scenes) {
            Map<FactorCode, BigDecimal> weights = weights(version, scene);
            WeightVersionRepository.GradeThresholdView thresholds = weightVersionRepository
                    .findGradeThreshold(version.getId(), scene.name())
                    .orElseThrow(() -> new IllegalStateException("缺少情境分級門檻：" + scene));
            ScoringEngine.Result result = scoringEngine.calculate(
                    values, weights, thresholds.getGradeAMin(), thresholds.getGradeBMin());
            ProductScore score = ProductScore.builder()
                    .product(source.getProduct())
                    .weightVersion(version)
                    .period(period)
                    .sceneType(scene)
                    .primary(scene == mainScene)
                    .active(true)
                    .bonusSubtotal(result.bonusSubtotal())
                    .penaltySubtotal(result.penaltySubtotal())
                    .finalScore(result.finalScore())
                    .grade(result.grade())
                    .confidence(confidence(source, classification))
                    .calculatedAt(calculatedAt)
                    .build();
            score.setFactors(cloneFactors(score, sourceFactors, result.effectiveWeights()));
            recalculated.add(score);
        }

        scoreRepository.deactivateCurrentScores(productId, period);
        return scoreRepository.saveAll(recalculated);
    }

    private static int confidence(ProductScore source, SceneClassificationResponse classification) {
        int confidence = source.getConfidence();
        if (classification.confidence() != null
                && classification.confidence().compareTo(SCENE_SCORING_CONFIDENCE) < 0) {
            confidence = Math.min(confidence, 90);
        }
        return confidence;
    }

    private static List<ScoreFactor> cloneFactors(
            ProductScore score,
            Map<FactorCode, ScoreFactor> source,
            Map<FactorCode, BigDecimal> effectiveWeights) {
        List<ScoreFactor> factors = new ArrayList<>();
        for (FactorCode code : FactorCode.values()) {
            ScoreFactor existing = source.get(code);
            boolean penalty = code.isPenalty();
            factors.add(ScoreFactor.builder()
                    .score(score)
                    .factorCode(code)
                    .rawValue(existing == null ? null : existing.getRawValue())
                    .normalizedValue(existing == null ? null : existing.getNormalizedValue())
                    .weight(penalty ? null : effectiveWeights.get(code).setScale(3, RoundingMode.HALF_UP))
                    .penaltyValue(penalty && existing != null ? existing.getPenaltyValue() : null)
                    .imputed(existing != null && existing.isImputed())
                    .penalty(penalty)
                    .dataAvailable(existing != null && existing.isDataAvailable())
                    .note(existing == null ? "本次評分無資料" : existing.getNote())
                    .build());
        }
        return factors;
    }

    private static Map<FactorCode, BigDecimal> weights(WeightVersion version, SceneType scene) {
        EnumMap<FactorCode, BigDecimal> weights = version.getProfiles().stream()
                .filter(profile -> profile.getSceneType() == scene)
                .collect(Collectors.toMap(
                        WeightProfile::getFactorCode,
                        WeightProfile::getWeight,
                        (first, ignored) -> first,
                        () -> new EnumMap<>(FactorCode.class)));
        return weights;
    }

    private static String isoWeekPeriod(LocalDate date) {
        WeekFields iso = WeekFields.ISO;
        return "%04dW%02d".formatted(
                date.get(iso.weekBasedYear()), date.get(iso.weekOfWeekBasedYear()));
    }
}
