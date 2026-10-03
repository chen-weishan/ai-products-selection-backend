package com.example.ssds.api.scene;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.score.dto.ScoreRecalculationResponse;
import com.example.ssds.api.scene.dto.SceneLogResponse;
import com.example.ssds.api.scene.dto.SceneOverrideRequest;
import com.example.ssds.api.scene.dto.SceneOverrideResponse;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreExecutionService;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 人工情境覆寫、正式重算與稽核紀錄的單一交易閉環。 */
@Service
public class SceneOverrideService {
    private final ProductRepository productRepository;
    private final SceneClassificationLogRepository sceneRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final ScoreExecutionService scoring;
    private final ObjectMapper objectMapper;

    public SceneOverrideService(
            ProductRepository productRepository,
            SceneClassificationLogRepository sceneRepository,
            AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository,
            ScoreExecutionService scoring,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.sceneRepository = sceneRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.scoring = scoring;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SceneOverrideResponse override(
            Long productId,
            SceneOverrideRequest request,
            String actorEmail,
            String sourceIp) {
        Product product = productRepository.findWithDetailsById(productId)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項：" + productId));
        if (product.getTrackType() != TrackType.A) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "只有 A 軌品項可覆寫評分情境");
        }
        AppUser actor = appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "登入使用者不存在或已失效"));
        SceneClassificationLog previous = sceneRepository
                .findFirstByProductIdOrderByCreatedAtDesc(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_STATE_TRANSITION,
                        "此品項尚無情境判定，無法進行人工覆寫"));

        SceneClassificationLog overridden = sceneRepository.saveAndFlush(
                SceneClassificationLog.builder()
                        .product(product)
                        .aiSceneType(previous.getAiSceneType())
                        .aiConfidence(previous.getAiConfidence())
                        .aiReasoning(previous.getAiReasoning())
                        .alternativeSceneType(previous.getAlternativeSceneType())
                        .signals(previous.getSignals())
                        .finalSceneType(request.sceneType())
                        .overriddenBy(actor)
                        .overrideReason(request.reason().trim())
                        .fallbackApplied(previous.isFallbackApplied())
                        .fallbackReason(previous.getFallbackReason())
                        .model(previous.getModel())
                        .promptVersion(previous.getPromptVersion())
                        .heatBucket(previous.getHeatBucket())
                        .period(previous.getPeriod())
                        .build());

        Instant attemptedAt = Instant.now();
        EvaluationResult result = scoring.evaluate(new EvaluationCommand(
                productId,
                request.sceneType(),
                null,
                null,
                false,
                attemptedAt));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("finalSceneType", previous.getFinalSceneType());
        before.put("classificationId", previous.getId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("finalSceneType", request.sceneType());
        after.put("classificationId", overridden.getId());
        after.put("overrideReason", request.reason().trim());
        after.put("primaryScoreId", result.primaryScoreId());
        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action("OVERRIDE")
                .entityType("SceneClassification")
                .entityId(overridden.getId())
                .beforeJson(json(before))
                .afterJson(json(after))
                .ip(sourceIp)
                .build());

        return new SceneOverrideResponse(
                SceneLogResponse.from(overridden),
                new ScoreRecalculationResponse(
                        productId,
                        result.status(),
                        result.primaryScoreId(),
                        result.message()));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法建立情境覆寫稽核內容", exception);
        }
    }
}
