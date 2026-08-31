package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.infra.entity.id.HeatCompositeDailyId;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** v3.0 §7.2.3：每關鍵字每日的多來源合成熱度。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "heat_composite_daily")
@IdClass(HeatCompositeDailyId.class)
public class HeatCompositeDaily {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "keyword_id", nullable = false)
    private TrendKeyword keyword;

    @Id
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "composite_value", nullable = false, precision = 6, scale = 2)
    private BigDecimal compositeValue;

    @Column(name = "slope_7d", precision = 8, scale = 4)
    private BigDecimal slope7d;

    @Column(name = "slope_30d", precision = 8, scale = 4)
    private BigDecimal slope30d;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HeatStage stage;

    @Column(name = "stage_weeks", nullable = false)
    private short stageWeeks;

    @Column(name = "estimated_lifespan_days")
    private Integer estimatedLifespanDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_weights", nullable = false, columnDefinition = "jsonb")
    private String appliedWeights;

    @Column(name = "divergence_flag", nullable = false)
    private boolean divergenceFlag;

    @Column(name = "volume_below_floor", nullable = false)
    private boolean volumeBelowFloor;
}
