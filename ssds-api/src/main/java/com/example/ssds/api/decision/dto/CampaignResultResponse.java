package com.example.ssds.api.decision.dto;

import com.example.ssds.core.domain.PostNoteCode;
import com.example.ssds.core.domain.SelloutStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 結案回填內容（§7.2.8 campaign_result）。比率為 0–1。 */
public record CampaignResultResponse(
        int actualQty,
        SelloutStatus selloutStatus,
        BigDecimal returnRate,
        BigDecimal realizedMarginRate,
        PostNoteCode postNoteCode,
        String postNoteText,
        Long filledById,
        String filledByName,
        OffsetDateTime filledAt) {
}
