package com.example.ssds.api.scoring;

import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 不呼叫 LLM 的正式評分批次；完整母體只準備一次，品項快照各自獨立提交。 */
@Service
public class PureScoringBatchService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final ProductRepository productRepository;
    private final SceneClassificationLogRepository sceneRepository;
    private final ScoringFactorBatchService factorBatchService;
    private final ScoreExecutionService scoreExecutionService;

    public PureScoringBatchService(
            ProductRepository productRepository,
            SceneClassificationLogRepository sceneRepository,
            ScoringFactorBatchService factorBatchService,
            ScoreExecutionService scoreExecutionService) {
        this.productRepository = productRepository;
        this.sceneRepository = sceneRepository;
        this.factorBatchService = factorBatchService;
        this.scoreExecutionService = scoreExecutionService;
    }

    public BatchResult evaluateAll(Instant attemptedAt) {
        return evaluate(null, attemptedAt);
    }

    public BatchResult evaluateProductIds(Collection<Long> productIds, Instant attemptedAt) {
        return evaluate(new LinkedHashSet<>(productIds), attemptedAt);
    }

    /** 銷售匯入完成後只重算該批次實際影響的品項，不建立 AI task。 */
    public BatchResult evaluateImportBatch(Long importBatchId, Instant attemptedAt) {
        return evaluateProductIds(
                productRepository.findProductIdsByImportBatch(importBatchId), attemptedAt);
    }

    private BatchResult evaluate(Set<Long> requestedIds, Instant attemptedAt) {
        List<Product> population = productRepository.findScorable(TrackType.A);
        List<Product> targets = requestedIds == null
                ? population
                : population.stream().filter(product -> requestedIds.contains(product.getId())).toList();
        if (targets.isEmpty()) {
            return new BatchResult(0, 0, 0, List.of(), List.of());
        }

        PreparedPopulation factorPopulation = factorBatchService.preparePopulation(
                population, attemptedAt.atZone(BUSINESS_ZONE).toLocalDate());
        Map<Long, Map<FactorCode, FactorInput>> factorsByProduct = factorPopulation.allFactors();
        Map<Long, SceneClassificationLog> latestScenes = sceneRepository.findLatestByProductIds(
                        targets.stream().map(Product::getId).toList())
                .stream()
                .collect(Collectors.toMap(
                        log -> log.getProduct().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        int scored = 0;
        int insufficient = 0;
        List<ItemResult> results = new java.util.ArrayList<>();
        List<ItemFailure> failures = new java.util.ArrayList<>();
        for (Product product : targets) {
            try {
                Map<FactorCode, FactorInput> factors = factorsByProduct.get(product.getId());
                if (factors == null) {
                    throw new IllegalStateException("九因子批次未回傳品項：" + product.getId());
                }
                EvaluationResult result = scoreExecutionService.evaluatePrepared(
                        command(product.getId(), latestScenes.get(product.getId()), attemptedAt),
                        factors);
                results.add(new ItemResult(
                        product.getId(), result.status(), result.primaryScoreId(), result.message()));
                if (result.scored()) {
                    scored++;
                } else {
                    insufficient++;
                }
            } catch (RuntimeException exception) {
                failures.add(new ItemFailure(product.getId(), safeMessage(exception)));
            }
        }
        return new BatchResult(
                targets.size(), scored, insufficient, results, failures, factorPopulation);
    }

    private static EvaluationCommand command(
            Long productId, SceneClassificationLog scene, Instant attemptedAt) {
        if (scene == null) {
            return new EvaluationCommand(
                    productId, SceneType.REPLENISHMENT, null, null, true, attemptedAt);
        }
        SceneType alternative = scene.isFallbackApplied() || scene.isOverridden()
                ? null
                : scene.getAlternativeSceneType();
        return new EvaluationCommand(
                productId,
                scene.getFinalSceneType(),
                alternative,
                scene.isOverridden() ? null : scene.getAiConfidence(),
                scene.isFallbackApplied() && !scene.isOverridden(),
                attemptedAt);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public record BatchResult(
            int attemptedCount,
            int scoredCount,
            int insufficientCount,
            List<ItemResult> results,
            List<ItemFailure> failures,
            PreparedPopulation factorPopulation) {
        public BatchResult(
                int attemptedCount,
                int scoredCount,
                int insufficientCount,
                List<ItemResult> results,
                List<ItemFailure> failures) {
            this(attemptedCount, scoredCount, insufficientCount, results, failures, null);
        }

        public BatchResult {
            results = List.copyOf(results);
            failures = List.copyOf(failures);
        }

        public int failedCount() {
            return failures.size();
        }
    }

    public record ItemResult(
            Long productId, LastScoringStatus status, Long primaryScoreId, String message) {}

    public record ItemFailure(Long productId, String message) {}
}
