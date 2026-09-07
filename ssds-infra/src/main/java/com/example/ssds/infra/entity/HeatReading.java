package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.*;

/**
 * 各來源熱度原始讀值（規格書 §7.2 heat_reading）。
 *
 * <p>§5.3.2：各來源量級不可比（Threads 的則數與 Google Trends 的指數不是
 * 同一個世界），因此<b>先在來源內百分位化再依合成權重加總</b>。
 * 合成時實際採用的是 {@link #percentileWithinSource}，不是 {@link #rawValue}。
 *
 * <p>§7.2.3（V17）：keyword_id／category_id 兩個維度擇一——關鍵字級來源
 * （THREADS、GOOGLE_TRENDS）填 keyword，品類級來源（INSTAGRAM）填 category。
 * 資料庫的 CHECK constraint（ck_heat_reading_target）保證兩者至少填一個。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "heat_reading")
public class HeatReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private HeatSource source;

    /** 關鍵字級來源使用；品類級來源（如 INSTAGRAM）此欄為 NULL，改用 {@link #category}。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "keyword_id", nullable = true)
    private TrendKeyword keyword;

    /** 品類級來源使用；關鍵字級來源此欄為 NULL。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "category_id", nullable = true)
    private Category category;

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @Column(name = "raw_value", nullable = false, precision = 12, scale = 3)
    private BigDecimal rawValue;

    /** 同來源內百分位（0–100）。 */
    @Column(name = "percentile_within_source", precision = 5, scale = 2)
    private BigDecimal percentileWithinSource;
}