package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * ISO 週，如 2026W30，以 Asia/Taipei 判定（§7.2.6）。
     *
     * <p>欄位型別為 CHAR(7) 而非 VARCHAR。Hibernate 對 String 預設推導出
     * VARCHAR，與 bpchar 不符會讓 {@code ddl-auto=validate} 在啟動時失敗，
     * 因此必須以 {@code @JdbcTypeCode} 明確指定 CHAR。
     *
     * <p>資料庫端另有格式約束 {@code ck_score_period_format}
     * （四位年 + W + 兩位週次，週次 01–53），寫入前應先自行驗證，
     * 否則會在 flush 當下才收到約束違反。
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7, columnDefinition = "char(7)")
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene_type", nullable = false, length = 24)
    private SceneType sceneType;

    /**
     * 主情境那筆為 true，次要情境為 false（§FR-04 多情境評分）。
     *
     * <p>SceneClassifierAgent 的 {@code sceneType} 為主情境、
     * {@code alternativeScene} 為次要情境，兩者各產生一筆本實體。
     * FR-05 品項詳情預設顯示主情境；FR-11 決策綁定的也是主情境那筆。
     */
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private boolean primary = true;

    /**
     * 同 (product, period, sceneType) 重複評分時僅最新一筆為 true（§5.10）。
     *
     * <p>舊紀錄保留不刪除，因此排行查詢必須自行過濾 {@code is_active = true}，
     * 否則同一品項會出現多列歷史分數。
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * 加分小計 Σ(w_i × normalized_i)，值域 0–100（§5.5）。
     *
     * <p>等於 {@link #factors} 中各加分列 {@code normalized_value × weight} 的總和，
     * FR-05「分數組成」畫面上長條的加總對應的就是這一欄，**不做二次換算**。
     *
     * <p>v1.0 的 base_score 已於 V17 移除：那一欄與本欄語意重複，
     * §5.5 的公式只用 bonusSubtotal 與 penaltySubtotal。
     * 百分位正規化只套用在單一因子層級（{@link ScoreFactor#getNormalizedValue()}）。
     */
    @Column(name = "bonus_subtotal", nullable = false, precision = 5, scale = 2)
    private BigDecimal bonusSubtotal;

    /** 扣分小計，上限 40（§5.5）。扣分不做百分位換算（§5.2.2：扣分因子固定生效）。 */
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
