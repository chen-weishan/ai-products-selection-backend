package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.AiTaskType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

/** Durable daily counters used to restore all AI usage, including calls outside ai_task. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ai_budget_usage_daily",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_budget_usage_daily_date_pool",
                columnNames = {"usage_date", "budget_pool"}))
public class AiBudgetUsageDaily {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_pool", nullable = false, length = 16)
    private AiTaskType.BudgetPool budgetPool;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "cache_hit_count", nullable = false)
    private int cacheHitCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
