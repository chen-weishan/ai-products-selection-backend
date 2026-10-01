package com.example.ssds.api.decision.dto;

import com.example.ssds.core.domain.DecisionType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 建立決策（規格書 §FR-11-1、§8.2 POST /products/{id}/decisions）。
 *
 * <p>評分快照、AI 建議、決策人一律由後端決定，不接受前端傳入——
 * 讓前端指定 scoreId 等於允許綁到次要情境或舊評分，AC-11-1 就守不住。
 *
 * <p>{@code firstOrderQty} 在 ADOPT 時必填（資料庫 ck_decision_qty），
 * {@code reason} 在未採納 AI 建議時必填（AC-11-2、ck_decision_reason），
 * 兩者都依決策類型而定，由 service 驗證而非註解。
 */
public record CreateDecisionRequest(
        @NotNull DecisionType decision,
        @Positive Integer firstOrderQty,
        LocalDate expectedListDate,
        @Size(max = 500) String reason) {
}
