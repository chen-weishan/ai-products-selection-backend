package com.example.ssds.api.scoring;

import com.example.ssds.api.product.service.ProductFallbackScoringService;
import com.example.ssds.api.product.service.InsufficientDataException;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.scoring.ScoringEngine;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** 將本次 Agent 1 的適用情境套入現行權重，建立 v3.0 多情境評分快照。 */
@Service
public class ScoreRecalculationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final BigDecimal SCENE_SCORING_CONFIDENCE = new BigDecimal("0.70");

    private final ProductScoreRepository scoreRepository;
    private final WeightVersionRepository weightVersionRepository;
    private final ProductRepository productRepository;
    private final ProductFallbackScoringService initialScoring;
    private final RiskAlertRepository riskAlertRepository;
    private final ScoringEngine scoringEngine = new ScoringEngine();

    public ScoreRecalculationService(
            ProductScoreRepository scoreRepository,
            WeightVersionRepository weightVersionRepository) {
        this(scoreRepository, weightVersionRepository, null, null, null);
    }

    public ScoreRecalculationService(
            ProductScoreRepository scoreRepository,
            WeightVersionRepository weightVersionRepository,
            ProductRepository productRepository,
            ProductFallbackScoringService initialScoring) {
        this(scoreRepository, weightVersionRepository, productRepository, initialScoring, null);
    }

    @Autowired
    public ScoreRecalculationService(
            ProductScoreRepository scoreRepository,
            WeightVersionRepository weightVersionRepository,
            ProductRepository productRepository,
            ProductFallbackScoringService initialScoring,
            RiskAlertRepository riskAlertRepository) {
        this.scoreRepository = scoreRepository;
        this.weightVersionRepository = weightVersionRepository;
        this.productRepository = productRepository;
        this.initialScoring = initialScoring;
        this.riskAlertRepository = riskAlertRepository;
    }

    @Transactional
    public Result recalculate(Long productId, SceneClassificationResponse classification) {
        ProductScore source;
        try {
            source = scoreRepository
                    .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(productId)
                    .orElseGet(() -> createInitialScore(productId));
        } catch (InsufficientDataException exception) {
            return insufficient(productId, exception.getMessage());
        }
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
        try {
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
        } catch (ScoringEngine.InsufficientScoringDataException exception) {
            return insufficient(productId, exception.getMessage());
        }

        scoreRepository.deactivateCurrentScores(productId, period);
        List<ProductScore> saved = scoreRepository.saveAll(recalculated);
        markScoringAttempt(source.getProduct(), LastScoringStatus.SCORED);
        return new Result(LastScoringStatus.SCORED, saved, null);
    }

    /** 新增正式 A 軌品項尚無歷史快照時，先建立可降級的真實資料基準分數。 */
    private ProductScore createInitialScore(Long productId) {
        if (productRepository == null || initialScoring == null) {
            throw new IllegalStateException("此品項尚無可供重算的六因子快照");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalStateException("找不到待評分品項：" + productId));
        return initialScoring.buildScore(product);
    }

    private Result insufficient(Long productId, String message) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalStateException("找不到待評分品項：" + productId));
        markScoringAttempt(product, LastScoringStatus.INSUFFICIENT_DATA);
        if (riskAlertRepository != null && !riskAlertRepository
                .existsByProductIdAndRiskTypeAndStatus(productId, "DATA_INSUFFICIENT", AlertStatus.OPEN)) {
            riskAlertRepository.save(RiskAlert.builder()
                    .product(product)
                    .riskType("DATA_INSUFFICIENT")
                    .severity(Severity.MEDIUM)
                    .triggerValue(message)
                    .build());
        }
        return new Result(LastScoringStatus.INSUFFICIENT_DATA, List.of(), message);
    }

    private void markScoringAttempt(Product product, LastScoringStatus status) {
        product.setLastScoringStatus(status);
        product.setLastScoringAttemptedAt(Instant.now());
        if (productRepository != null) productRepository.save(product);
    }

    public record Result(
            LastScoringStatus status,
            List<ProductScore> scores,
            String message) {
        public Result {
            scores = List.copyOf(scores);
        }

        public boolean scored() {
            return status == LastScoringStatus.SCORED;
        }
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
