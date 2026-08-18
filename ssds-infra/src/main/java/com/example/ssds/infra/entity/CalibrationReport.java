package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.CalibrationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 季度權重校準報告（規格書 §7.2 calibration_report、FR-15）。
 *
 * <p>分工原則：<b>統計決定數字，AI 負責解釋與提出假設，人負責核准。</b>
 * AC-15-2：建議權重必須由 {@link #regressionResult} 的迴歸結果產生，
 * {@link #aiInterpretation} 只做解讀，不得自行產生數值。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "calibration_report")
public class CalibrationReport {

    /** AC-15-1：樣本數低於此值時畫面必須顯示效度警示，且警示不可關閉。 */
    public static final int MIN_VALID_SAMPLE_SIZE = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 如 2026Q3。 */
    @Column(nullable = false, unique = true, length = 8)
    private String quarter;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    /** 各因子相關係數與建議權重。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "regression_result", nullable = false, columnDefinition = "jsonb")
    private String regressionResult;

    @Column(name = "ai_interpretation", columnDefinition = "text")
    private String aiInterpretation;

    /** 平權／現行版本／建議版本三者的比較（AC-15-4）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "backtest_result", columnDefinition = "jsonb")
    private String backtestResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private CalibrationStatus status = CalibrationStatus.PENDING;

    /** AC-15-3：須經 BUYER_LEAD 核准才產生新權重版本。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 樣本不足時，報告只能當作趨勢觀察，不足以支持權重調整。 */
    public boolean isStatisticallyValid() {
        return sampleSize >= MIN_VALID_SAMPLE_SIZE;
    }
}
