package com.example.ssds.api.imports.service;

import com.example.ssds.api.product.service.InsufficientDataException;
import com.example.ssds.api.product.service.ProductFallbackScoringService;
import com.example.ssds.core.domain.LastScoringStatus;
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
    private final ProductFallbackScoringService scoringService;

    public ImportScoreRecalculationItemService(
            ProductRepository productRepository,
            SceneClassificationLogRepository sceneRepository,
            ProductFallbackScoringService scoringService
    ) {
        this.productRepository = productRepository;
        this.sceneRepository = sceneRepository;
        this.scoringService = scoringService;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Result recalculate(Long productId) {
        Product product = productRepository.findWithDetailsById(productId).orElse(null);
        if (product == null || !product.isScorable() || product.getDeletedAt() != null) {
            return Result.SKIPPED;
        }

        var latestScene = sceneRepository.findFirstByProductIdOrderByCreatedAtDesc(productId);
        SceneType scene = latestScene.map(SceneClassificationLog::getFinalSceneType)
                .orElse(SceneType.REPLENISHMENT);
        try {
            scoringService.score(product, scene);
            product.setLastScoringStatus(LastScoringStatus.SCORED);
            product.setLastScoringAttemptedAt(Instant.now());
            if (latestScene.isEmpty()) {
                sceneRepository.save(defaultSceneLog(product));
            }
            return Result.SCORED;
        } catch (InsufficientDataException error) {
            product.setLastScoringStatus(LastScoringStatus.INSUFFICIENT_DATA);
            product.setLastScoringAttemptedAt(Instant.now());
            return Result.INSUFFICIENT_DATA;
        }
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
