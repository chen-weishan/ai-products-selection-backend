package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.HeatStage;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Agent 5 的每關鍵字判定歷史；資料表已由 V8 建立。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trend_interpretation")
public class TrendInterpretation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "keyword_id", nullable = false)
    private TrendKeyword keyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "heat_stage", nullable = false, length = 16)
    private HeatStage heatStage;

    @Column(name = "stage_weeks", nullable = false)
    private short stageWeeks;

    @Column(name = "estimated_lifespan_days", nullable = false)
    private int estimatedLifespanDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private String inputSnapshot;

    @Column(name = "fallback_applied", nullable = false)
    private boolean fallbackApplied;

    @Column(name = "fallback_reason", length = 64)
    private String fallbackReason;

    @Column(length = 80)
    private String model;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "is_current", nullable = false)
    private boolean current;
}
