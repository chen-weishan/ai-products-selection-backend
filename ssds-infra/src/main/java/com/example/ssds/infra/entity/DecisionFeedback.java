package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/**
 * 結案回填（規格書 §7.2 decision_feedback、FR-11-2）。
 *
 * <p>FR-11-2 明訂欄位壓在 5 個以內：欄位一多就沒人填，
 * 沒人填 FR-15 的權重校準就沒有標籤資料可用，整套機制形同虛設。
 *
 * <p>與 {@link DecisionRecord} 1:1 共用主鍵。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "decision_feedback")
public class DecisionFeedback {

    @Id
    @Column(name = "decision_id")
    private Long decisionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id")
    private DecisionRecord decision;

    @Column(name = "actual_qty_sold", nullable = false)
    private int actualQtySold;

    @Column(name = "actual_cvr", precision = 6, scale = 4)
    private BigDecimal actualCvr;

    @Column(name = "sellout_days")
    private Integer selloutDays;

    @Column(name = "return_rate", precision = 5, scale = 4)
    private BigDecimal returnRate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "filled_by", nullable = false)
    private AppUser filledBy;

    @Column(name = "filled_at", nullable = false)
    @Builder.Default
    private Instant filledAt = Instant.now();
}
