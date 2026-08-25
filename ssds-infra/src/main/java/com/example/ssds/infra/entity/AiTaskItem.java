package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.TaskItemStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * AI 任務逐項結果（規格書 §7.2 ai_task_item）。
 *
 * <p>FR-07 支援「重跑失敗項」，所以失敗列要保留可重試的狀態與
 * {@link #errorMessage}。
 *
 * <p>v2.0 的 raw_response 已於 v3.0 移除（§7.2.7）：LLM 原始回應可能含
 * 經模型改寫的評論片段，長期存 DB 會擴大機敏資料暴露面。
 * 除錯所需的原文改記於應用日誌（§10），保留期依日誌政策，不進資料庫。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_task_item")
public class AiTaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private AiTask task;

    /** 權重校準等非品項層級的任務為 null。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TaskItemStatus status = TaskItemStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** 以關鍵字為標的的任務（TREND_INTERPRET／SOURCING_SCOUT）填這欄，與 product 擇一。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "keyword_id")
    private TrendKeyword keyword;

    @Column(name = "duration_ms")
    private Integer durationMs;
}
