package com.example.ssds.infra.entity;

import com.example.ssds.infra.entity.id.ClimateNormalId;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/**
 * 歷史同期氣候統計（規格書 §7.2 climate_normal、FR-17-2）。
 *
 * <p>只有這份「歷史同期統計」可以進評分。AC-17-4 明訂短期天氣預報不得計入
 * 分數：預報僅 7–14 天可信，而團購備貨週期常超過三週，時序對不上，
 * 硬做會生出「看似有用但永遠來不及反應」的功能。預報只用於開團時機提醒。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "climate_normal")
@IdClass(ClimateNormalId.class)
public class ClimateNormal {

    @Id
    @Column(name = "region_code", nullable = false, length = 16)
    private String regionCode;

    @Id
    @Column(nullable = false)
    private Short month;

    @Column(name = "avg_temp", nullable = false, precision = 4, scale = 1)
    private BigDecimal avgTemp;

    /** 降雨機率百分比（0–100）。 */
    @Column(name = "rain_probability", nullable = false, precision = 4, scale = 1)
    private BigDecimal rainProbability;
}
