package com.example.ssds.infra.entity;

import com.example.ssds.infra.entity.id.TrendDailyId;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;

/**
 * 關鍵字每日熱度（規格書 §7.2 trend_daily）。
 *
 * <p>heat_value 已正規化為 0–100，§5.3.3 的 7 日／30 日斜率由本表計算。
 * §7.3：主鍵即查詢鍵，90 日區間查詢為索引範圍掃描，不需另建索引。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trend_daily")
@IdClass(TrendDailyId.class)
public class TrendDaily {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "keyword_id", nullable = false)
    private TrendKeyword keyword;

    @Id
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "heat_value", nullable = false)
    private int heatValue;
}
