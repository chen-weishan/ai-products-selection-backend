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
     * 目前生效中的版本。資料庫端的 partial unique index 保證最多一筆，
     * 因此這裡回傳 Optional 而非 List。
     */
    @EntityGraph(attributePaths = {"profiles"})
    Optional<WeightVersion> findByStatus(WeightVersionStatus status);

    Optional<WeightVersion> findByVersionNo(String versionNo);

    List<WeightVersion> findAllByOrderByCreatedAtDesc();

    /** 評分時要連權重明細一起取，否則每個因子都會多一次查詢。 */
    @EntityGraph(attributePaths = {"profiles"})
    Optional<WeightVersion> findWithProfilesById(Long id);
}
