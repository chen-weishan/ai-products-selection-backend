package com.example.ssds.infra.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 品類層級的預設適溫區間（規格書 §FR-17-2、V13 建表）。
 *
 * <p>品項未填 {@code ideal_temp_min/max} 時沿用此值；本表與品項皆無資料時，
 * CLIMATE 因子標為無資料，不扣分，權重依 §5.7 分攤（AC-17-5）。
 *
 * <p>與 {@link Category} 1:1 共用主鍵，寫法比照 {@link CategoryLeadTime}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "category_climate_profile")
public class CategoryClimateProfile {

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "ideal_temp_min", nullable = false, precision = 4, scale = 1)
    private BigDecimal idealTempMin;

    @Column(name = "ideal_temp_max", nullable = false, precision = 4, scale = 1)
    private BigDecimal idealTempMax;

    /**
     * 適配度衰減的容忍範圍（°C），{@code fit = max(0, 1 − distance / tolerance)}。
     *
     * <p>欄位為 NOT NULL，DB 端有 {@code DEFAULT 12.0}，但 JPA 的 INSERT 一律帶上本欄，
     * DB 預設值吃不到。留 null 交由 Service 以 {@code ClimateProperties.defaultTolerance}
     * 填入，系統級預設值才有唯一的來源。
     */
    @Column(name = "tolerance", nullable = false, precision = 4, scale = 1)
    private BigDecimal tolerance;
}
