package com.example.ssds.infra.entity.id;

import java.io.Serializable;
import java.time.LocalDate;
import lombok.*;

/** heat_composite_daily 的複合主鍵（keyword_id, stat_date）。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class HeatCompositeDailyId implements Serializable {
    private Long keyword;
    private LocalDate statDate;
}
