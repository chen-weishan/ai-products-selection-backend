package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.id.GradeThresholdId;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/**
 * 四榜（情境）各自的 A／B 分級門檻，隨 {@link WeightVersion} 一併版本化
 * （規格書 §FR-08、AC-08-6、AC-04-5；表由 V13 建立）。
 *
 * <p>門檻維度是「榜」而非「品類」—— v2.0 兩種說法並存，v3.0 裁決為榜（§15 C-11）。
 *
 * <p>注意：{@link WeightVersion} 上<b>沒有</b>指向本表的 @OneToMany，
 * 因此本表不受 cascade 影響，新增與修改都必須自己呼叫 repository。
 * 這是刻意的：門檻與權重的讀取時機不同，綁在一起會讓 findAll 永遠多 JOIN 一張表。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "grade_threshold")
@IdClass(GradeThresholdId.class)
public class GradeThreshold {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private WeightVersion version;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "scene_type", nullable = false, length = 24)
    private SceneType sceneType;

    /** A 級下限（含）。DB CHECK 保證 grade_a_min > grade_b_min（V13 L158）。 */
    @Column(name = "grade_a_min", nullable = false, precision = 5, scale = 2)
    private BigDecimal gradeAMin;

    /** B 級下限（含）；低於此值為 C 級。 */
    @Column(name = "grade_b_min", nullable = false, precision = 5, scale = 2)
    private BigDecimal gradeBMin;
}
