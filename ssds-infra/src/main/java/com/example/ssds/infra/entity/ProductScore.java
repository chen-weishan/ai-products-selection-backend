package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.*;

/**
 * 選品分數（規格書 §7.2 product_score、§5.5）。
 *
 * <p>§5.10：每次評分產生一筆新紀錄，保留歷史、不覆寫。決策綁定的是某一筆
 * 歷史列，所以權重改版之後回頭看，當時的分數依然是當時的分數（AC-11-6）。
 *
 * <p>B 軌品項不評分（AC-16-2），本表只有 A 軌資料。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_score")
public class ProductScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** AC-08-4：可回溯這筆分數當時用的是哪一版權重。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weight_version_id", nullable = false)
    private WeightVersion weightVersion;

    /** ISO 週，如 2026W30。 */
    @Column(nullable = false, length = 8)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene_type", nullable = false, length = 24)
    private SceneType sceneType;

    /**
     * 加權和 Σ(w_i × normalized_i)，尚未做同品類百分位換算。
     * 等於 {@link #factors} 中各加分列 {@code normalized_value × weight} 的總和，
     * 因此 FR-05「分數組成」畫面上長條的加總對應的是這一欄，不是 {@link #bonusSubtotal}。
     */
    @Column(name = "base_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal baseScore;

    /**
     * 加分小計：{@link #baseScore} 經 §5.3.1 同品類百分位換算後的值（0–100）。
     *
     * <p>§5.5 的計算範例即為此步驟：加權和 76.3 換算後得 91，再減扣分 4 得 87。
     * 換算函式為 {@code percentile_rank(x, same_category_values) × 100}；
     * 同品類樣本數 < 10 時退回全品類百分位，並依 §5.9 扣 20 點信心度。
     *
     * <p>也就是百分位正規化在本系統套用兩次：一次在單一因子層級
     * （{@link ScoreFactor#getNormalizedValue()}），一次在加權後的總分層級。
     */
    @Column(name = "bonus_subtotal", nullable = false, precision = 5, scale = 2)
    private BigDecimal bonusSubtotal;

    /**
     * v1.0 欄位名，與 {@link #penaltySubtotal} 為同一個值，一律同步寫入。
     * 扣分不做百分位換算（§5.2.2：扣分因子固定生效）。
     */
    @Column(name = "risk_penalty", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal riskPenalty = BigDecimal.ZERO;

    /** 扣分小計，上限 40（§5.5）。 */
    @Column(name = "penalty_subtotal", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal penaltySubtotal = BigDecimal.ZERO;

    /** max(0, 加分小計 − 扣分小計)。 */
    @Column(name = "final_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal finalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Grade grade;

    /** 0–100，扣分規則見 §5.9。低於 50 時 UI 顯示警示標記。 */
    @Column(nullable = false)
    @Builder.Default
    private int confidence = 100;

    @Column(name = "calculated_at", nullable = false)
    @Builder.Default
    private Instant calculatedAt = Instant.now();

    @OneToMany(mappedBy = "score", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScoreFactor> factors = new ArrayList<>();

    /** §5.6 硬規則：扣分達 20 以上者強制進入風險示警清單，且分級最高只給 B。 */
    public boolean isRiskSuppressed() {
        return penaltySubtotal != null
                && penaltySubtotal.compareTo(BigDecimal.valueOf(20)) >= 0;
    }

    /** §5.9：信心度低於 50 時前端須顯示警示標記。 */
    public boolean isLowConfidence() {
        return confidence < 50;
    }
}
