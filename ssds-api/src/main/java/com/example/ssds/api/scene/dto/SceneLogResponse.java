package com.example.ssds.api.scene.dto;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.SceneClassificationLog;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** AI 情境判定與人工覆寫的可稽核歷程。 */
public record SceneLogResponse(
        Long classificationId,
        Long productId,
        String period,
        SceneType aiSceneType,
        BigDecimal aiConfidence,
        String aiReasoning,
        SceneType alternativeSceneType,
        List<String> signals,
        SceneType finalSceneType,
        boolean overridden,
        Long overriddenByUserId,
        String overriddenByDisplayName,
        String overrideReason,
        boolean fallbackApplied,
        String fallbackReason,
        OffsetDateTime createdAt) {

    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    public static SceneLogResponse from(SceneClassificationLog log) {
        AppUser actor = log.getOverriddenBy();
        Instant createdAt = log.getCreatedAt();
        return new SceneLogResponse(
                log.getId(),
                log.getProduct().getId(),
                log.getPeriod(),
                log.getAiSceneType(),
                log.getAiConfidence(),
                log.getAiReasoning(),
                log.getAlternativeSceneType(),
                log.getSignals() == null ? List.of() : List.copyOf(log.getSignals()),
                log.getFinalSceneType(),
                log.isOverridden(),
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getDisplayName(),
                log.getOverrideReason(),
                log.isFallbackApplied(),
                log.getFallbackReason(),
                createdAt == null ? null : createdAt.atZone(API_ZONE).toOffsetDateTime());
    }
}
