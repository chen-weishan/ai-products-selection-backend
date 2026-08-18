package com.example.ssds.infra.entity.id;

import java.io.Serializable;
import java.time.LocalDate;
import lombok.*;

/**
 * trend_daily 的複合主鍵（keyword_id, stat_date）。
 *
 * <p>用 {@code @IdClass} 而非 {@code @EmbeddedId}：查詢條件幾乎都是
 * 「某關鍵字的某段日期區間」，@IdClass 可以直接寫 {@code where keyword.id = ?
 * and statDate between ? and ?}，不必每次穿過一層 id 屬性。
 *
 * <p>複合主鍵類別必須實作 Serializable、有無參建構子，並正確實作
 * equals/hashCode —— Hibernate 用它們在一級快取中辨識實體。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TrendDailyId implements Serializable {

    private Long keyword;
    private LocalDate statDate;
}
