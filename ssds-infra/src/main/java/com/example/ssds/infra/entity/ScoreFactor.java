package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.FactorCode;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/**
 * 單一因子的評分明細（規格書 §7.2 score_factor）。
 *
 * <p>這張表是「因子評分透明化」的資料基礎，畫面上每根長條直接對應一列。
 *
 * <p>加分列有 {@link #weight}、沒有 {@link #penaltyValue}；扣分列相反
 * （§5.2.2：扣分因子固定生效、不參與權重）。資料庫端以 CHECK 約束把這個
 * 形狀釘住，避免寫入時兩邊都填而語意不明。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "score_factor")
public class ScoreFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "score_id", nullable = false)
    private ProductScore score;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_code", nullable = false, length = 32)
    private FactorCode factorCode;

    /** 原始值，如毛利率 0.42、熱度斜率 3.4。 */
    @Column(name = "raw_value", precision = 12, scale = 4)
    private BigDecimal rawValue;

    /** 同品類百分位正規化後的 0–100（§5.3.1）。 */
    @Column(name = "normalized_value", precision = 5, scale = 2)
    private BigDecimal normalizedValue;

    /** 當時權重；扣分列為 null。 */
    @Column(precision = 4, scale = 3)
    private BigDecimal weight;

    /** 扣分列的實際扣分值（正數表示扣掉多少）；加分列為 null。 */
    /** 扣分因子的扣分（正值）；加分因子為 null。§7.2.6 指定 DECIMAL(4,1)。 */
    @Column(name = "penalty_value", precision = 4, scale = 1)
    private BigDecimal penaltyValue;

    /** 是否為缺值填補（如退回全品類百分位）。 */
    @Column(name = "is_imputed", nullable = false)
    @Builder.Default
    private boolean imputed = false;

    @Column(name = "is_penalty", nullable = false)
    @Builder.Default
    private boolean penalty = false;

    /**
     * false 表該因子無資料：UI 標灰底，權重按比例分攤給其餘因子，且不扣分（§5.7）。
     * 新品項與 B 軌品項天生缺歷史資料，若以扣分處理就永遠進不了排行前段。
     */
    @Column(name = "data_available", nullable = false)
    @Builder.Default
    private boolean dataAvailable = true;

    /** 本因子對加分小計的貢獻（正規化值 × 權重）；扣分列回傳 0。 */
    public BigDecimal contribution() {
        if (penalty || weight == null || normalizedValue == null) {
            return BigDecimal.ZERO;
        }
        return normalizedValue.multiply(weight);
    }
}
