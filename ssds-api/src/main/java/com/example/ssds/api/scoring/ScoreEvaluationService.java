package com.example.ssds.api.scoring;

import com.example.ssds.api.score.ScoringResultRecorder;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.scoring.ScoringEngine;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 正式評分寫入契約。因子值由呼叫端提供，本服務只負責驗證、計分及原子性建立快照。
 * {@link ScoreExecutionService} 會將 Phase 2 的九因子結果組成 {@link EvaluationRequest}；
 * 其他流程不得繞過本服務直接落庫。
 */
@Service
public class ScoreEvaluationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final ProductRepository productRepository;
    private final ProductScoreRepository scoreRepository;
    private final WeightVersionRepository weightVersionRepository;
    private final ScoringResultRecorder resultRecorder;
    private final ScoringEngine scoringEngine = new ScoringEngine();

    public ScoreEvaluationService(
            ProductRepository productRepository,
            ProductScoreRepository scoreRepository,
            WeightVersionRepository weightVersionRepository,
            ScoringResultRecorder resultRecorder) {
        this.productRepository = productRepository;
        this.scoreRepository = scoreRepository;
        this.weightVersionRepository = weightVersionRepository;
        this.resultRecorder = resultRecorder;
    }

    /**
     * 同一交易內建立本次所有情境快照。任何一步失敗時，舊 active 快照不會被永久停用。
     */
    @Transactional
    public EvaluationResult evaluate(EvaluationRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new IllegalStateException("找不到待評分品項：" + request.productId()));
        if (!product.isScorable()) {
            throw new IllegalArgumentException("B 軌品項不得建立正式評分快照：" + request.productId());
        }

        WeightVersion version = weightVersionRepository.findByIsCurrentTrue()
                .orElseThrow(() -> new IllegalStateException("目前沒有生效中的權重版本"));
        Map<FactorCode, ScoringEngine.FactorValue> engineValues = engineValues(request.factors());
        int availableBonusCount = availableBonusCount(request.factors());
        String period = isoWeekPeriod(request.attemptedAt().atZone(BUSINESS_ZONE).toLocalDate());

        LinkedHashSet<SceneType> scenes = new LinkedHashSet<>();
        scenes.add(request.primaryScene());
        if (request.alternativeScene() != null) {
            scenes.add(request.alternativeScene());
        }

        List<ProductScore> newScores = new ArrayList<>();
        try {
            for (SceneType scene : scenes) {
                Map<FactorCode, BigDecimal> configuredWeights = weights(version, scene);
                WeightVersionRepository.GradeThresholdView thresholds = weightVersionRepository
                        .findGradeThreshold(version.getId(), scene.name())
                        .orElseThrow(() -> new IllegalStateException("缺少情境分級門檻：" + scene));
                ScoringEngine.Result calculation = scoringEngine.calculate(
                        engineValues,
                        configuredWeights,
                        thresholds.getGradeAMin(),
                        thresholds.getGradeBMin());
                ProductScore score = ProductScore.builder()
                        .product(product)
                        .weightVersion(version)
                        .period(period)
                        .sceneType(scene)
                        .primary(scene == request.primaryScene())
                        .active(true)
                        .bonusSubtotal(calculation.bonusSubtotal())
                        .penaltySubtotal(calculation.penaltySubtotal())
                        .finalScore(calculation.finalScore())
                        .grade(calculation.grade())
                        .confidence(request.confidence())
                        .calculatedAt(request.attemptedAt())
                        .build();
                score.setFactors(toScoreFactors(score, request.factors(), calculation.effectiveWeights()));
                newScores.add(score);
            }
        } catch (ScoringEngine.InsufficientScoringDataException exception) {
            resultRecorder.recordInsufficientData(product, availableBonusCount, request.attemptedAt());
            return EvaluationResult.insufficient(period, version.getId(), exception.getMessage());
        }

        // 先更新舊列是為了符合 active partial unique index；交易失敗會一併回滾此更新。
        scoreRepository.deactivateCurrentScores(product.getId(), period);
        List<ProductScore> saved = scoreRepository.saveAllAndFlush(newScores);
        resultRecorder.recordScored(product, request.attemptedAt());

        List<ScoreSnapshot> snapshots = saved.stream()
                .map(score -> new ScoreSnapshot(score.getId(), score.getSceneType(), score.isPrimary()))
                .toList();
        Long primaryScoreId = saved.stream()
                .filter(ProductScore::isPrimary)
                .map(ProductScore::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("評分結果缺少主情境快照"));
        return EvaluationResult.scored(period, version.getId(), primaryScoreId, snapshots);
    }

    private static Map<FactorCode, ScoringEngine.FactorValue> engineValues(
            Map<FactorCode, FactorInput> factors) {
        EnumMap<FactorCode, ScoringEngine.FactorValue> values = new EnumMap<>(FactorCode.class);
        factors.forEach((code, input) -> values.put(code, new ScoringEngine.FactorValue(
                input.normalizedValue(), input.penaltyValue(), input.dataAvailable())));
        return values;
    }

    private static int availableBonusCount(Map<FactorCode, FactorInput> factors) {
        return (int) factors.entrySet().stream()
                .filter(entry -> !entry.getKey().isPenalty())
                .filter(entry -> entry.getValue().dataAvailable())
                .filter(entry -> entry.getValue().normalizedValue() != null)
                .count();
    }

    private static List<ScoreFactor> toScoreFactors(
            ProductScore score,
            Map<FactorCode, FactorInput> inputs,
            Map<FactorCode, BigDecimal> effectiveWeights) {
        List<ScoreFactor> factors = new ArrayList<>();
        for (FactorCode code : FactorCode.values()) {
            FactorInput input = inputs.get(code);
            factors.add(ScoreFactor.builder()
                    .score(score)
                    .factorCode(code)
                    .rawValue(input.rawValue())
                    .normalizedValue(input.normalizedValue())
                    .weight(code.isPenalty()
                            ? null
                            : effectiveWeights.get(code).setScale(3, RoundingMode.HALF_UP))
                    .penaltyValue(code.isPenalty() ? input.penaltyValue() : null)
                    .imputed(input.imputed())
                    .penalty(code.isPenalty())
                    .dataAvailable(input.dataAvailable())
                    .note(input.note())
                    .drivingKeywordId(input.drivingKeywordId())
                    .drivingFestivalId(input.drivingFestivalId())
                    .build());
        }
        return factors;
    }

    private static Map<FactorCode, BigDecimal> weights(WeightVersion version, SceneType scene) {
        return version.getProfiles().stream()
                .filter(profile -> profile.getSceneType() == scene)
                .collect(Collectors.toMap(
                        WeightProfile::getFactorCode,
                        WeightProfile::getWeight,
                        (first, ignored) -> first,
                        () -> new EnumMap<>(FactorCode.class)));
    }

    private static String isoWeekPeriod(LocalDate date) {
        WeekFields iso = WeekFields.ISO;
        return "%04dW%02d".formatted(
                date.get(iso.weekBasedYear()), date.get(iso.weekOfWeekBasedYear()));
    }

    /** 正式快照的完整輸入；九因子由 {@link ScoreExecutionService} 從權威資料批次取得。 */
    public record EvaluationRequest(
            Long productId,
            SceneType primaryScene,
            SceneType alternativeScene,
            int confidence,
            Instant attemptedAt,
            Map<FactorCode, FactorInput> factors) {
        public EvaluationRequest {
            Objects.requireNonNull(productId, "productId 不可為 null");
            Objects.requireNonNull(primaryScene, "primaryScene 不可為 null");
            Objects.requireNonNull(attemptedAt, "attemptedAt 不可為 null");
            Objects.requireNonNull(factors, "factors 不可為 null");
            if (confidence < 0 || confidence > 100) {
                throw new IllegalArgumentException("confidence 必須介於 0 到 100");
            }
            EnumMap<FactorCode, FactorInput> copy = new EnumMap<>(FactorCode.class);
            copy.putAll(factors);
            if (copy.size() != FactorCode.values().length) {
                throw new IllegalArgumentException("正式評分必須提供完整九因子");
            }
            for (FactorCode code : FactorCode.values()) {
                FactorInput input = Objects.requireNonNull(copy.get(code), "缺少因子：" + code);
                validateFactor(code, input);
            }
            factors = Map.copyOf(copy);
        }

        private static void validateFactor(FactorCode code, FactorInput input) {
            if (input.drivingKeywordId() != null && code != FactorCode.TREND) {
                throw new IllegalArgumentException("只有 TREND 可指定 drivingKeywordId");
            }
            if (input.drivingFestivalId() != null && code != FactorCode.FESTIVAL) {
                throw new IllegalArgumentException("只有 FESTIVAL 可指定 drivingFestivalId");
            }
            if (code.isPenalty()) {
                if (input.normalizedValue() != null) {
                    throw new IllegalArgumentException(code + " 扣分因子不得提供 normalizedValue");
                }
                BigDecimal penalty = input.penaltyValue();
                if (input.dataAvailable() && penalty == null) {
                    throw new IllegalArgumentException(code + " 有資料時必須提供 penaltyValue");
                }
                if (penalty != null && (penalty.signum() < 0
                        || penalty.compareTo(BigDecimal.valueOf(code.maxPenalty())) > 0)) {
                    throw new IllegalArgumentException(code + " penaltyValue 超出允許範圍");
                }
                if (!input.dataAvailable() && penalty != null && penalty.signum() != 0) {
                    throw new IllegalArgumentException(code + " 無資料時不得產生扣分");
                }
                return;
            }
            if (input.penaltyValue() != null) {
                throw new IllegalArgumentException(code + " 加分因子不得提供 penaltyValue");
            }
            BigDecimal normalized = input.normalizedValue();
            if (input.dataAvailable() && normalized == null) {
                throw new IllegalArgumentException(code + " 有資料時必須提供 normalizedValue");
            }
            if (!input.dataAvailable() && normalized != null) {
                throw new IllegalArgumentException(code + " 無資料時 normalizedValue 必須為 null");
            }
            if (normalized != null && (normalized.signum() < 0
                    || normalized.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new IllegalArgumentException(code + " normalizedValue 必須介於 0 到 100");
            }
        }
    }

    public record FactorInput(
            BigDecimal rawValue,
            BigDecimal normalizedValue,
            BigDecimal penaltyValue,
            boolean dataAvailable,
            boolean imputed,
            String note,
            Long drivingKeywordId,
            Long drivingFestivalId) {
        public FactorInput(
                BigDecimal rawValue,
                BigDecimal normalizedValue,
                BigDecimal penaltyValue,
                boolean dataAvailable,
                boolean imputed,
                String note) {
            this(rawValue, normalizedValue, penaltyValue, dataAvailable, imputed, note, null, null);
        }
    }

    public record ScoreSnapshot(Long scoreId, SceneType sceneType, boolean primary) {}

    public record EvaluationResult(
            LastScoringStatus status,
            String period,
            Long weightVersionId,
            Long primaryScoreId,
            List<ScoreSnapshot> snapshots,
            String message) {
        public EvaluationResult {
            snapshots = List.copyOf(snapshots);
        }

        private static EvaluationResult scored(
                String period, Long versionId, Long primaryScoreId, List<ScoreSnapshot> snapshots) {
            return new EvaluationResult(
                    LastScoringStatus.SCORED, period, versionId, primaryScoreId, snapshots, null);
        }

        private static EvaluationResult insufficient(String period, Long versionId, String message) {
            return new EvaluationResult(
                    LastScoringStatus.INSUFFICIENT_DATA, period, versionId, null, List.of(), message);
        }

        public boolean scored() {
            return status == LastScoringStatus.SCORED;
        }
    }
}
