package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.WeightProfile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 情境權重組（規格書 §7.2 weight_profile、FR-08）。 */
@Repository
public interface WeightProfileRepository extends JpaRepository<WeightProfile, Long> {

    List<WeightProfile> findByVersionIdAndSceneType(Long versionId, SceneType sceneType);

    List<WeightProfile> findByVersionId(Long versionId);

    /**
     * AC-08-1 的存檔前驗證：同一 version + scene 的權重加總須為 1。
     * 這是跨列條件，SQL CHECK 表達不了，只能在應用層檢查。
     */
    @Query("""
            select coalesce(sum(p.weight), 0)
            from WeightProfile p
            where p.version.id = :versionId and p.sceneType = :sceneType
            """)
    java.math.BigDecimal sumWeights(
            @Param("versionId") Long versionId, @Param("sceneType") SceneType sceneType);
}
