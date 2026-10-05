package com.example.ssds.api.decision.dto;

import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 決策清單列與決策詳情（§8.2 GET /decisions、GET /decisions/{id}）。
 *
 * <p>清單與詳情共用同一個形狀，前端不必維護兩套型別；清單的 {@code result}
 * 同樣帶出，S-12 左側清單點選後不必再打一次詳情。
 *
 * <p>分數欄位是決策綁定的那筆 {@code product_score}（AC-11-6：之後重算不影響）。
 */
public record DecisionResponse(
        Long id,
        Long productId,
        String productName,
        String categoryName,
        /** 品項目前狀態（§7.4），S-12 用來判斷是否已開團。 */
        ProductStatus productStatus,
        DecisionType decision,
        /** AI 建議的動作；決策當下沒有 AI 建議時為 null。 */
        DecisionType aiAction,
        boolean followedAi,
        Integer aiQtyMin,
        Integer aiQtyMax,
        Integer firstOrderQty,
        LocalDate expectedListDate,
        LocalDate campaignEndDate,
        String reason,
        Long decidedById,
        String decidedByName,
        OffsetDateTime decidedAt,
        Long reviewedById,
        String reviewedByName,
        OffsetDateTime reviewedAt,
        Long scoreId,
        /** 綁定評分的 period（ISO 週或年月，依評分資料）。 */
        String period,
        SceneType sceneType,
        BigDecimal finalScore,
        Grade grade,
        DecisionStage stage,
        /**
         * 回填逾期天數 = 今日 − 結案日 − 7（§FR-11-2、AC-11-3）。
         * 僅在已結案未回填且大於 0 時有值，其餘為 null。
         */
        Integer overdueDays,
        CampaignResultResponse result) {
}
