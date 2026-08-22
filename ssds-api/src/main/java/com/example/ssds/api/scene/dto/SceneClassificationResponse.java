package com.example.ssds.api.scene.dto;

import com.example.ssds.ai.model.SceneClassificationResult;
import com.example.ssds.ai.model.SceneCode;
import com.example.ssds.infra.entity.SceneClassificationLog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

public record SceneClassificationResponse(
        Long classificationId,
        Long productId,
        SceneCode sceneType,
        BigDecimal confidence,
        String reasoning,
        SceneCode alternativeScene,
        List<String> signals,
        boolean fallbackApplied,
        String fallbackReason,
        boolean cacheHit,
        String heatBucket,
        String model,
        String promptVersion,
        OffsetDateTime classifiedAt
) {
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    public static SceneClassificationResponse from(
            SceneClassificationLog log,
            SceneClassificationResult result) {
        return new SceneClassificationResponse(
                log.getId(),
                log.getProduct().getId(),
                result.output().sceneType(),
                result.output().confidence(),
                result.output().reasoning(),
                result.output().alternativeScene(),
                result.output().signals(),
                result.fallbackApplied(),
                result.fallbackReason() == null ? null : result.fallbackReason().name(),
                result.cacheHit(),
                log.getHeatBucket(),
                result.model(),
                result.promptVersion(),
                toApiTime(log.getCreatedAt()));
    }

    public static SceneClassificationResponse from(SceneClassificationLog log) {
        return new SceneClassificationResponse(
                log.getId(),
                log.getProduct().getId(),
                SceneCode.fromDomain(log.getFinalSceneType()),
                log.getAiConfidence(),
                log.getAiReasoning(),
                log.getAlternativeSceneType() == null ? null : SceneCode.fromDomain(log.getAlternativeSceneType()),
                log.getSignals(),
                log.isFallbackApplied(),
                log.getFallbackReason(),
                false,
                log.getHeatBucket(),
                log.getModel(),
                log.getPromptVersion(),
                toApiTime(log.getCreatedAt()));
    }

    private static OffsetDateTime toApiTime(Instant value) {
        return value == null ? null : value.atZone(API_ZONE).toOffsetDateTime();
    }
}
