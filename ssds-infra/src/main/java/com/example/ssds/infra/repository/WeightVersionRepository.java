package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.WeightVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 權重版本（規格書 §7.2 weight_version、FR-08）。 */
@Repository
public interface WeightVersionRepository extends JpaRepository<WeightVersion, Long> {

    /**
     * 目前生效中的版本（{@code GET /weight-versions/active} 就是查這個）。
     *
     * <p>資料庫端的 partial unique index {@code uk_weight_version_current}
     * 保證最多一筆，因此回傳 Optional 而非 List。
     */
    @EntityGraph(attributePaths = {"profiles"})
    Optional<WeightVersion> findByIsCurrentTrue();

    /**
     * 取生效中版本並加行鎖（{@code SELECT ... FOR UPDATE}），供核准流程切換 current 使用。
     *
     * <p>不能重用 {@link #findByIsCurrentTrue()}：那支帶 {@code @EntityGraph}，
     * 會對 profiles 做 left join，PostgreSQL 不允許對 outer join 的可為空側下 FOR UPDATE。
     * 這裡只需要版本列本身，不取權重明細。
     *
     * <p>沒有任何 current 版本時鎖不到列，此時仍靠
     * partial unique index {@code uk_weight_version_current} 擋住併發。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from WeightVersion v where v.isCurrent = true")
    Optional<WeightVersion> findCurrentForUpdate();

    /**
     * 依狀態查。**回傳 List 不是 Optional**：v3.0 起 APPROVED 只代表「已核准」，
     * 被新版取代的舊版仍然是 APPROVED，同一狀態可以有很多筆。
     * 要找生效中的那一筆請用 {@link #findByIsCurrentTrue()}。
     */
    List<WeightVersion> findByStatus(WeightVersionStatus status);

    Optional<WeightVersion> findByVersionNo(String versionNo);

    List<WeightVersion> findAllByOrderByCreatedAtDesc();

    /** 評分時要連權重明細一起取，否則每個因子都會多一次查詢。 */
    @EntityGraph(attributePaths = {"profiles"})
    Optional<WeightVersion> findWithProfilesById(Long id);

    @Query(value = """
            select grade_a_min as "gradeAMin", grade_b_min as "gradeBMin"
            from grade_threshold
            where version_id = :versionId and scene_type = :sceneType
            """, nativeQuery = true)
    Optional<GradeThresholdView> findGradeThreshold(
            @Param("versionId") Long versionId, @Param("sceneType") String sceneType);

    interface GradeThresholdView {
        BigDecimal getGradeAMin();
        BigDecimal getGradeBMin();
    }
}
