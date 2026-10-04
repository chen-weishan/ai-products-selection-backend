package com.example.ssds.api.decision.dto;

import com.example.ssds.core.domain.PostNoteCode;
import com.example.ssds.core.domain.SelloutStatus;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 結案回填（規格書 §FR-11-2、§8.2 POST /decisions/{id}/result）。
 *
 * <p>五個欄位（事後註記的代碼與文字算同一欄）。比率一律 0–1、四位小數（§7.2.8 DECIMAL(5,4)），
 * 前端顯示百分比時自行換算，不在 API 上傳 41.2 這種值。
 */
public record CampaignResultRequest(
        @NotNull @PositiveOrZero Integer actualQty,
        @NotNull SelloutStatus selloutStatus,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal returnRate,
        @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4)
        BigDecimal realizedMarginRate,
        PostNoteCode postNoteCode,
        @Size(max = 255) String postNoteText) {
}
