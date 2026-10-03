package com.example.ssds.api.score;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.score.dto.ScoreRecalculationResponse;
import com.example.ssds.api.scoring.PureScoringBatchService;
import com.example.ssds.api.scoring.PureScoringBatchService.BatchResult;
import com.example.ssds.api.scoring.PureScoringBatchService.ItemResult;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Phase 6 的人工單品純評分入口；不建立或執行 AI task。 */
@Service
public class ScoreCommandService {
    private final ProductRepository productRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final PureScoringBatchService scoring;
    private final ObjectMapper objectMapper;

    public ScoreCommandService(
            ProductRepository productRepository,
            AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository,
            PureScoringBatchService scoring,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.scoring = scoring;
        this.objectMapper = objectMapper;
    }

    public ScoreRecalculationResponse recalculate(
            Long productId, String actorEmail, String sourceIp) {
        Product product = productRepository.findById(productId)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項：" + productId));
        AppUser actor = actor(actorEmail);

        BatchResult batch = scoring.evaluateProductIds(List.of(productId), Instant.now());
        if (batch.attemptedCount() == 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "此品項目前不在正式評分範圍：" + productId);
        }
        if (!batch.failures().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "品項重算失敗：" + batch.failures().getFirst().message());
        }

        ItemResult result = batch.results().getFirst();
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("productId", product.getId());
        auditDetails.put("status", result.status().name());
        auditDetails.put("primaryScoreId", result.primaryScoreId());
        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action("RECALCULATE")
                .entityType("ProductScore")
                .entityId(result.primaryScoreId())
                .afterJson(json(auditDetails))
                .ip(sourceIp)
                .build());
        return new ScoreRecalculationResponse(
                productId, result.status(), result.primaryScoreId(), result.message());
    }

    private AppUser actor(String actorEmail) {
        return appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "登入使用者不存在或已失效"));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法建立評分稽核內容", exception);
        }
    }
}
