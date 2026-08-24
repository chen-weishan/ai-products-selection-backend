package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.WeightVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
