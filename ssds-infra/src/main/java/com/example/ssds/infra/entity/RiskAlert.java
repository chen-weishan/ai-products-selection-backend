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
     * 示警類型。Java 端是字串，資料庫端<b>是列舉</b>——V17 起
     * {@code ck_risk_alert_type} 限定值域，V27 再加上 {@code DATA_INSUFFICIENT}：
     * REVIEW_RISK／LOGISTICS_RISK／INVENTORY_RISK／PENALTY_CAP／HEAT_CRASH／
     * HEAT_SURGE／SEASON_MISMATCH／FESTIVAL_WINDOW_CLOSING／LOW_CONFIDENCE／
     * DATA_INSUFFICIENT（§FR-10-1 九項，第十項見 V27 的註解）。
     *
     * <p>不做成 Java 列舉是因為 §FR-10-1 的規則仍在增修，多一個值就要同時改
     * 列舉與 migration；但寫入前務必確認值在上述清單內，否則會被資料庫的
     * CHECK 擋下來。長度為 32（V17 由 30 放寬）。
     */
    @Column(name = "risk_type", nullable = false, length = 32)
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
