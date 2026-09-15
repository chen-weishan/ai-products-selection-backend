package com.example.ssds.infra.entity.id;

import com.example.ssds.core.domain.SceneType;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@code grade_threshold} 的複合主鍵 (version_id, scene_type)（V13 L156）。
 *
 * <p>欄位名必須與 {@code GradeThreshold} 上標 @Id 的屬性同名；
 * {@code version} 在 entity 是 WeightVersion，在這裡是它的主鍵型別 Long ——
 * 這是 JPA 衍生識別（derived identifier）的規定，不是筆誤。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class GradeThresholdId implements Serializable {

    private Long version;

    private SceneType sceneType;
}
