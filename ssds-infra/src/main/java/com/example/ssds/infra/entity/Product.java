package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.*;

/**
 * 品項主檔（規格書 §7.2 product）。
 *
 * <p>A/B 雙軌共用一張表，以 {@link #trackType} 區分：
 * A 軌已有供應商與成本，跑完整因子評分；B 軌只看到熱度、還沒找到貨，
 * 不產生選品分數，改以時效落差排序（§5.8）。
 * 成案後由 B 改 A，探索期間累積的熱度資料一併帶入、不需重抓（AC-16-5）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product")
public class Product extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** B 軌尚未找到供應商時為 null。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(precision = 10, scale = 2)
    private BigDecimal cost;

    @Column(name = "suggested_price", precision = 10, scale = 2)
    private BigDecimal suggestedPrice;

    /**
     * 冗餘欄位（售價－成本）／售價，寫入時由 {@link #recalculateMarginRate()} 算好。
     * 不做成 JPA {@code @Formula}，因為排行與篩選要能直接吃索引。
     */
    @Column(name = "margin_rate", precision = 5, scale = 4)
    private BigDecimal marginRate;

    /** 最小訂購量。偏高會觸發 inventory_risk 扣分（§5.2.2）。 */
    private Integer moq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Season season = Season.ALL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ProductStatus status = ProductStatus.DRAFT;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "listed_at")
    private LocalDate listedAt;

    // ---- v2.0 新增欄位（§7.2 既有資料表變更）----

    @Enumerated(EnumType.STRING)
    @Column(name = "track_type", nullable = false, length = 8)
    @Builder.Default
    private TrackType trackType = TrackType.A;

    /** 僅 B 軌有值。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sourcing_status", length = 24)
    private SourcingStatus sourcingStatus;

    /** 冷鏈／易碎／常溫…，logistics_risk 扣分的判定輸入。 */
    @Column(name = "logistics_condition", length = 100)
    private String logisticsCondition;

    /** 效期天數，inventory_risk 扣分的判定輸入。 */
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    /**
     * 關聯關鍵字。join table 只有兩個外鍵、無自身屬性，故用 {@code @ManyToMany}。
     * 一個品項可綁多個關鍵字，熱度取合成值（§5.3.2）。
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "product_keyword",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "keyword_id"))
    @Builder.Default
    private Set<TrendKeyword> keywords = new LinkedHashSet<>();

    /**
     * 由成本與售價重算毛利率。成本或售價缺一即設為 null —— 不用 0 代替，
     * 0 會被 §5.3.1 的百分位正規化當成「毛利率最差」而不是「沒資料」，
     * 那正好違反 §5.7「資料不足不懲罰」。
     */
    public void recalculateMarginRate() {
        if (cost == null || suggestedPrice == null || suggestedPrice.signum() == 0) {
            this.marginRate = null;
            return;
        }
        this.marginRate = suggestedPrice
                .subtract(cost)
                .divide(suggestedPrice, 4, RoundingMode.HALF_UP);
    }

    /** B 軌不產生選品分數（AC-16-2）。 */
    public boolean isScorable() {
        return trackType == TrackType.A;
    }

    /**
     * FR-03-2 例外條件：成本 ≥ 售價 → 阻擋儲存並提示。
     *
     * <p>注意是<b>嚴格大於</b>：成本等於售價同樣要擋，因為毛利率會是 0，
     * 評分沒有意義。資料庫端的 ck_product_price 是同一條規則的最後一道防線，
     * 這裡先擋是為了能回傳可讀的錯誤訊息，而不是丟出約束違反例外。
     *
     * <p>成本或售價尚未填寫時回傳 true —— 「必填」由表單驗證負責，
     * 不是這條規則的職責。
     */
    public boolean isPricingAcceptable() {
        if (cost == null || suggestedPrice == null) {
            return true;
        }
        return suggestedPrice.compareTo(cost) > 0;
    }

    @PrePersist
    @PreUpdate
    void syncDerivedFields() {
        recalculateMarginRate();
    }
}
