package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.TaskStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * AI 任務逐項結果（規格書 §7.2 ai_task_item）。
 *
 * <p>FR-07 支援「重跑失敗項」，所以失敗列要保留可重試的狀態與
 * {@link #rawResponse} —— 免費模型的 JSON 格式遵循度不穩（§3.2），
 * Schema 驗證失敗時只有原文能還原現場。
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
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Column(name = "duration_ms")
    private Integer durationMs;
}
