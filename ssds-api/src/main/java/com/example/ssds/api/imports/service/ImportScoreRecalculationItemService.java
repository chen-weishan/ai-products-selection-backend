package com.example.ssds.api.imports.service;

import com.example.ssds.api.scoring.ScoreExecutionService;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** FR-09 單一品項的純計算重算；加入工作佇列提供的單一品項交易，且不建立 AI task。 */
@Service
public class ImportScoreRecalculationItemService {

    private final ProductRepository productRepository;
    private final SceneClassificationLogRepository sceneRepository;
    private final ScoreExecutionService scoringService;

    public ImportScoreRecalculationItemService(
            ProductRepository productRepository,
            SceneClassificationLogRepository sceneRepository,
            ScoreExecutionService scoringService
    ) {
        this.productRepository = productRepository;
        this.sceneRepository = sceneRepository;
        this.scoringService = scoringService;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Result recalculate(Long productId) {
        Product product = productRepository.findWithDetailsById(productId).orElse(null);
        if (product == null
                || !product.isScorable()
                || product.getDeletedAt() != null
                || product.getStatus() == ProductStatus.DRAFT
                || product.getStatus() == ProductStatus.REJECTED) {
            return Result.SKIPPED;
        }

        var latestScene = sceneRepository.findFirstByProductIdOrderByCreatedAtDesc(productId);
        Instant attemptedAt = Instant.now();
        var evaluation = scoringService.evaluate(command(productId, latestScene.orElse(null), attemptedAt));
        product.setLastScoringStatus(evaluation.status());
        product.setLastScoringAttemptedAt(attemptedAt);
        if (evaluation.status() == LastScoringStatus.INSUFFICIENT_DATA) {
            return Result.INSUFFICIENT_DATA;
        }
        if (latestScene.isEmpty()) {
            sceneRepository.save(defaultSceneLog(product));
        }
        return Result.SCORED;
    }

    private EvaluationCommand command(
            Long productId, SceneClassificationLog scene, Instant attemptedAt
    ) {
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

    private SceneClassificationLog defaultSceneLog(Product product) {
        return SceneClassificationLog.builder()
                .product(product)
                .finalSceneType(SceneType.REPLENISHMENT)
                .fallbackApplied(true)
                .fallbackReason("IMPORT_NO_PREVIOUS_SCENE")
                .heatBucket("UNKNOWN")
                .period(isoWeek(LocalDate.now(ZoneId.of("Asia/Taipei"))))
                .build();
    }

    private String isoWeek(LocalDate date) {
        WeekFields fields = WeekFields.ISO;
        return "%04dW%02d".formatted(
                date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear()));
    }

    public enum Result { SCORED, INSUFFICIENT_DATA, SKIPPED }
}
