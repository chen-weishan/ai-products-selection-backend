package com.example.ssds.api.decision.dto;

import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 建立決策前的判斷依據（GET /products/{id}/decision-context）。
 *
 * <p><b>規格補充</b>：§8.2 沒有這支端點。建立決策表單需要先看到「會綁哪筆評分」
 * 「AI 建議什麼」「依 §7.4 目前能選哪些決策」，這三件事都由後端判定，
 * 讓前端不必重寫一份狀態機與 AI 輸出解析。
 *
 * @param score            建立時會綁定的主情境評分；沒有時為 null，且 allowedDecisions 為空
 * @param ai               目前的 AI 進貨建議；沒有或無法解析時為 null
 * @param allowedDecisions 依 §7.4 目前可建立的決策類型
 * @param blockedReason    allowedDecisions 為空時的原因
 */
public record DecisionContextResponse(
        Long productId,
        String productName,
        ProductStatus productStatus,
        TrackType trackType,
        ScoreSummary score,
        AiSuggestion ai,
        List<DecisionType> allowedDecisions,
        String blockedReason) {

    public record ScoreSummary(Long scoreId, String period, SceneType sceneType, BigDecimal finalScore, Grade grade) {
    }

    public record AiSuggestion(DecisionType action, Integer qtyMin, Integer qtyMax, String quantityText,
            String reasoning) {
    }
}
