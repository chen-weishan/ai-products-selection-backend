package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * B 軌尋源候選（規格書 §7.2 sourcing_candidate、FR-16）。
 *
 * <p>AC-16-2：B 軌不產生選品分數，排序依據是<b>時效落差</b>而非熱度 ——
 * 熱度最高但來不及的品項排在前面不具意義。
 *
 * <p>時效落差是<b>否決條件</b>而非加權因子（FR-16-1 設計理由）：
 * 若寫成扣分，高熱度會把它蓋過去，但「來不及」是不可交易的事實。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sourcing_candidate")
public class SourcingCandidate extends BaseAuditEntity {

    /** §5.8：落差大於此天數視為可行，正常排序。 */
    public static final int FEASIBLE_GAP_DAYS = 14;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "keyword_id", nullable = false)
    private TrendKeyword keyword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "heat_stage", nullable = false, length = 16)
    private HeatStage heatStage;

    /** 停留於目前階段的週數。高原期第 3 週起壽命推估由 42 天降為 35 天。 */
    @Column(name = "stage_weeks")
    private Short stageWeeks;

    /** §5.8 初始經驗值：上升期 56、高原期 1–2 週 42、高原期 3 週以上 35、衰退期 17。 */
    @Column(name = "estimated_lifespan_days", nullable = false)
    private int estimatedLifespanDays;

    /** 預設取 category_lead_time，採購可覆寫（FR-16-2）。 */
    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    /** 時效落差 = 預估熱度剩餘壽命 − 預估尋源前置期。 */
    @Column(name = "time_gap_days", nullable = false)
    private int timeGapDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private SourcingStatus status = SourcingStatus.PENDING;

    @Column(name = "scout_report", columnDefinition = "text")
    private String scoutReport;

    /**
     * 依 §5.8 重算時效落差，並在落差為負時強制標記淘汰（AC-16-4）。
     * 落差 0～14 天標記為需加速尋源。
     */
    public void recalculateTimeGap() {
        this.timeGapDays = estimatedLifespanDays - leadTimeDays;
        if (timeGapDays < 0) {
            this.status = SourcingStatus.REJECTED;
        } else if (timeGapDays <= FEASIBLE_GAP_DAYS && status == SourcingStatus.PENDING) {
            this.status = SourcingStatus.URGENT;
        }
    }

    @PrePersist
    @PreUpdate
    void syncTimeGap() {
        recalculateTimeGap();
    }
}
