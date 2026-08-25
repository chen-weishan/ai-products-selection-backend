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
 *
 * <p><b>主關聯是品項而非關鍵字</b>（v3.0 §7.2.9 裁決）。v2.0 綁 keyword_id
 * 與 {@code product.track_type} 的模型互斥，AC-16-5「成案轉軌後熱度資料完整
 * 保留」沒有實作路徑；改綁品項後，轉軌只需改 track_type，熱度、標記與關鍵字
 * 關聯全部自然保留。
 *
 * <p>狀態不存於本實體，一律以 {@code product.sourcingStatus} 為準（§7.2.9）。
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

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    /**
     * <b>來源紀錄，不是即時關聯。</b>當初是從哪個關鍵字挖出這個候選的。
     *
     * <p>允許與 {@code product_keyword} 的現況不一致，也允許為 null
     * （轉軌後的品項可能已無對應關鍵字）。不要拿它做即時 join。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "keyword_id")
    private TrendKeyword keyword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "heat_stage", length = 16)
    private HeatStage heatStage;

    /** 停留於目前階段的週數。高原期第 3 週起壽命推估由 42 天降為 35 天。 */
    @Column(name = "stage_weeks")
    private Short stageWeeks;

    /** §5.8 初始經驗值：上升期 56、高原期 1–2 週 42、高原期 3 週以上 35、衰退期 17。 */
    @Column(name = "estimated_lifespan_days")
    private Integer estimatedLifespanDays;

    /** 預設取 category_lead_time，採購可覆寫（FR-16-2）。 */
    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    /** 覆寫前置期的採購（§7.2.9）。null 表示仍沿用 category_lead_time 的預設。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_time_overridden_by")
    private AppUser leadTimeOverriddenBy;

    /** 時效落差 = 預估熱度剩餘壽命 − 預估尋源前置期。壽命未知時為 null。 */
    @Column(name = "time_gap_days")
    private Integer timeGapDays;

    @Column(name = "scout_report", columnDefinition = "text")
    private String scoutReport;

    @Column(name = "scouted_at")
    private java.time.Instant scoutedAt;

    /**
     * 依 §5.8 重算時效落差，並在落差為負時強制標記淘汰（AC-16-4）。
     * 落差 0～14 天標記為需加速尋源。
     *
     * <p>狀態寫在 {@code product.sourcingStatus} 上：v3.0 §7.2.9 明訂狀態
     * 不重複於本表。壽命尚未推估（estimatedLifespanDays 為 null）時
     * 落差算不出來，維持 null 而不是填 0——0 的意思是「剛好來得及」。
     */
    public void recalculateTimeGap() {
        if (estimatedLifespanDays == null) {
            this.timeGapDays = null;
            return;
        }
        this.timeGapDays = estimatedLifespanDays - leadTimeDays;
        if (product == null) {
            return;
        }
        if (timeGapDays < 0) {
            product.setSourcingStatus(SourcingStatus.REJECTED);
        } else if (timeGapDays <= FEASIBLE_GAP_DAYS
                && product.getSourcingStatus() == SourcingStatus.PENDING) {
            product.setSourcingStatus(SourcingStatus.URGENT);
        }
    }

    @PrePersist
    @PreUpdate
    void syncTimeGap() {
        recalculateTimeGap();
    }
}
