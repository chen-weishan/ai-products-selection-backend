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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "keyword_id", nullable = false)
    private TrendKeyword keyword;

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @Column(name = "raw_value", nullable = false, precision = 12, scale = 3)
    private BigDecimal rawValue;

    /** 同來源內百分位（0–100）。 */
    @Column(name = "percentile_within_source", precision = 5, scale = 2)
    private BigDecimal percentileWithinSource;
}
