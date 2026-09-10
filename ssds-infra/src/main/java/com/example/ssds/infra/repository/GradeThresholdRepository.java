package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.id.GradeThresholdId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 四榜分級門檻（規格書 §FR-08、AC-08-6）。 */
@Repository
public interface GradeThresholdRepository
        extends JpaRepository<GradeThreshold, GradeThresholdId> {

    /** 一個版本固定四列。回傳 List 而非 Page —— 四列不需要分頁。 */
    List<GradeThreshold> findByVersionId(Long versionId);

    Optional<GradeThreshold> findByVersionIdAndSceneType(Long versionId, SceneType sceneType);
}
