package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * 風險示警（規格書 §7.2 risk_alert、FR-10）。
 *
 * <p>AC-10-4：扣分達 20 分以上的品項必定出現於本清單。
 * AC-10-2：已忽略者不做實體刪除，只是預設清單不顯示，可用篩選查回來。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "risk_alert")
public class RiskAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * 示警類型。刻意用自由字串而非列舉：FR-10 的示警來源除了三條扣分規則，
     * 還包含熱度急墜、季節性不匹配、供應商異常等會持續增修的規則，
     * 每加一種就改一次列舉與 migration 並不划算。
     */
    @Column(name = "risk_type", nullable = false, length = 30)
    private String riskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    /** 觸發當下的數值描述，例如「負評率 18%（門檻 15%）」。 */
    @Column(name = "trigger_value", length = 100)
    private String triggerValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private AlertStatus status = AlertStatus.OPEN;

    /** 忽略時必填（資料庫端亦有 CHECK 約束）。 */
    @Column(name = "ignore_reason", length = 300)
    private String ignoreReason;

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private Instant detectedAt = Instant.now();

    @Column(name = "handled_at")
    private Instant handledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private AppUser handledBy;
}
