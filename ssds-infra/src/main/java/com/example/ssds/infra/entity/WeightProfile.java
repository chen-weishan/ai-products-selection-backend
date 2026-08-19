package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/**
 * 情境權重組的單一因子權重（規格書 §7.2 weight_profile、FR-08）。
 *
 * <p>一個 {@link WeightVersion} × 四種 {@link SceneType} × 各加分因子 = 多列。
 * AC-08-1 要求同一 version + scene 的權重加總為 1，這是跨列的條件，
 * SQL CHECK 表達不了，由應用層在存檔前驗證。
 *
 * <p>本表<b>只放加分因子</b>。扣分因子固定生效、不參與權重（§5.2.2），
 * 放進來會讓「加總為 1」的規則失去意義。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "weight_profile")
public class WeightProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private WeightVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene_type", nullable = false, length = 24)
    private SceneType sceneType;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_code", nullable = false, length = 32)
    private FactorCode factorCode;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal weight;
}
