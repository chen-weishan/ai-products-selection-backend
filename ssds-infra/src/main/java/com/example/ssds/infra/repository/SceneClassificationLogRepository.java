package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.SceneClassificationLog;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 情境判定紀錄（規格書 §7.2 scene_classification_log）。 */
@Repository
public interface SceneClassificationLogRepository
        extends JpaRepository<SceneClassificationLog, Long> {

    Optional<SceneClassificationLog> findFirstByProductIdOrderByCreatedAtDesc(Long productId);

    List<SceneClassificationLog> findByProductIdOrderByCreatedAtDesc(Long productId);

    /** FR-11-3 情境判定覆寫率：分子。 */
    long countByOverriddenByIsNotNullAndCreatedAtBetween(Instant from, Instant to);

    /** 分母。 */
    long countByCreatedAtBetween(Instant from, Instant to);
}
