package com.example.ssds.api.scoring;

import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationRequest;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.ProductRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 將權威資料的九因子批次結果交給正式評分快照交易。 */
@Service
public class ScoreExecutionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final ProductRepository productRepository;
    private final ScoringFactorBatchService factorBatchService;
    private final ScoreEvaluationService evaluationService;

    public ScoreExecutionService(
            ProductRepository productRepository,
            ScoringFactorBatchService factorBatchService,
            ScoreEvaluationService evaluationService) {
        this.productRepository = productRepository;
        this.factorBatchService = factorBatchService;
        this.evaluationService = evaluationService;
    }

    /**
     * 百分位必須以完整可評分母體計算，因此即使只評一個品項，也先準備整批 A 軌資料。
     * 寫入交易仍由 {@link ScoreEvaluationService} 管理，本方法不把唯讀準備與落庫綁成長交易。
     */
    public EvaluationResult evaluate(EvaluationCommand command) {
        List<Product> population = productRepository.findScorable(TrackType.A);
        boolean targetIncluded = population.stream()
                .anyMatch(product -> product.getId().equals(command.productId()));
        if (!targetIncluded) {
            throw new IllegalArgumentException("品項不在正式評分範圍：" + command.productId());
        }

        Map<Long, Map<FactorCode, FactorInput>> factorsByProduct = factorBatchService.prepare(
                population,
                command.attemptedAt().atZone(BUSINESS_ZONE).toLocalDate());
        Map<FactorCode, FactorInput> factors = factorsByProduct.get(command.productId());
        if (factors == null) {
            throw new IllegalStateException("九因子批次未回傳目標品項：" + command.productId());
        }

        return evaluatePrepared(command, factors);
    }

    /** FULL_ANALYSIS 共用 task 級母體，只刷新 Agent 2 可能改變的目標品項 evidence。 */
    public EvaluationResult evaluate(
            EvaluationCommand command, PreparedPopulation factorPopulation) {
        return evaluatePrepared(
                command,
                factorBatchService.refreshTarget(factorPopulation, command.productId()));
    }

    EvaluationResult evaluatePrepared(
            EvaluationCommand command, Map<FactorCode, FactorInput> factors) {
        return evaluationService.evaluate(new EvaluationRequest(
                command.productId(),
                command.primaryScene(),
                command.alternativeScene(),
                confidence(factors, command.sceneConfidence(), command.sceneFallbackApplied()),
                command.attemptedAt(),
                factors));
    }

    /** §5.9 可由目前九因子與 Agent 1 輸出直接判定的信心度扣分。 */
    private static int confidence(
            Map<FactorCode, FactorInput> factors,
            BigDecimal sceneConfidence,
            boolean sceneFallbackApplied) {
        int deductions = factors.entrySet().stream()
                .filter(entry -> !entry.getKey().isPenalty())
                .mapToInt(entry -> {
                    FactorInput input = entry.getValue();
                    if (!input.dataAvailable()) {
                        return 8;
                    }
                    return input.imputed() ? 4 : 0;
                })
                .sum();
        if (sceneFallbackApplied
                || (sceneConfidence != null
                        && sceneConfidence.compareTo(new BigDecimal("0.70")) < 0)) {
            deductions += 10;
        }
        return Math.max(0, 100 - deductions);
    }

    public record EvaluationCommand(
            Long productId,
            SceneType primaryScene,
            SceneType alternativeScene,
            BigDecimal sceneConfidence,
            boolean sceneFallbackApplied,
            Instant attemptedAt) {
        public EvaluationCommand(
                Long productId,
                SceneType primaryScene,
                SceneType alternativeScene,
                BigDecimal sceneConfidence,
                Instant attemptedAt) {
            this(productId, primaryScene, alternativeScene, sceneConfidence, false, attemptedAt);
        }

        public EvaluationCommand {
            Objects.requireNonNull(productId, "productId 不可為 null");
            Objects.requireNonNull(primaryScene, "primaryScene 不可為 null");
            Objects.requireNonNull(attemptedAt, "attemptedAt 不可為 null");
            if (sceneConfidence != null
                    && (sceneConfidence.signum() < 0
                            || sceneConfidence.compareTo(BigDecimal.ONE) > 0)) {
                throw new IllegalArgumentException("sceneConfidence 必須介於 0 到 1");
            }
        }
    }
}
