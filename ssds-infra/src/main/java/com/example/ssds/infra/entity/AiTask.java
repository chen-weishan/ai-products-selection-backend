package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/**
 * AI 批次任務（規格書 §7.2 ai_task、FR-07）。
 *
 * <p>{@link #taskType} 同時決定預算池：SOURCING_SCOUT 走 B 軌池，
 * 耗盡不得影響 A 軌批次評分（AC-07-2），因此配額必須依池別分開計算。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_task")
public class AiTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private AiTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "total_count", nullable = false)
    @Builder.Default
    private int totalCount = 0;

    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private int successCount = 0;

    @Column(name = "fail_count", nullable = false)
    @Builder.Default
    private int failCount = 0;

    @Column(name = "total_cost_usd", nullable = false, precision = 10, scale = 5)
    @Builder.Default
    private BigDecimal totalCostUsd = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** AC-07-1：任務執行中可即時查看進度。 */
    public int progressPercent() {
        if (totalCount == 0) {
            return 0;
        }
        return (successCount + failCount) * 100 / totalCount;
    }

    public AiTaskType.BudgetPool budgetPool() {
        return taskType.budgetPool();
    }
}
