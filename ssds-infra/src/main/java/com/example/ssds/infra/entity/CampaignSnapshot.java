package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 開團當下的評分快照（規格書 §7.2 campaign_snapshot）。
 *
 * <p>因子值以 JSON 就地凍結，而不是 join 回 score_factor：後者的列會隨著
 * 每次重新評分而新增，回頭查時語意會漂移。AC-11-6 要的是「當時的樣子」，
 * 所以直接複製一份。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "campaign_snapshot")
public class CampaignSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false, unique = true)
    private DecisionRecord decision;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Grade grade;

    @Column(name = "bonus_subtotal", nullable = false, precision = 5, scale = 2)
    private BigDecimal bonusSubtotal;

    @Column(name = "penalty_subtotal", nullable = false, precision = 5, scale = 2)
    private BigDecimal penaltySubtotal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weight_version_id", nullable = false)
    private WeightVersion weightVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene_type", nullable = false, length = 24)
    private SceneType sceneType;

    /** true 表情境被人工覆寫；FR-11-3 的覆寫率指標由此統計。 */
    @Column(name = "scene_overridden", nullable = false)
    @Builder.Default
    private boolean sceneOverridden = false;

    /** 各因子原始值與正規化值。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factor_values", nullable = false, columnDefinition = "jsonb")
    private String factorValues;

    /** 當時各熱度來源狀態，用於事後解釋「這筆分數少了哪些來源」。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_availability", columnDefinition = "jsonb")
    private String sourceAvailability;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
