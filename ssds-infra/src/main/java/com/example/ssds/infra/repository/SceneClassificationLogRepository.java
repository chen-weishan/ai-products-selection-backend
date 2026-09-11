package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.SceneClassificationLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 情境判定紀錄（規格書 §7.2 scene_classification_log）。 */
@Repository
public interface SceneClassificationLogRepository
        extends JpaRepository<SceneClassificationLog, Long> {

    Optional<SceneClassificationLog> findFirstByProductIdOrderByCreatedAtDesc(Long productId);

    List<SceneClassificationLog> findByProductIdOrderByCreatedAtDesc(Long productId);

    /**
     * FR-02 排行的人工覆寫標記：批次取每個品項「最新一筆」判定紀錄。
     * 覆寫與否由服務層檢查（{@code overriddenBy} 非空且情境相符），
     * 因此這裡不過濾覆寫 —— 若只查覆寫列，會把「曾覆寫但後來重新 AI 判定」的品項誤標。
     */
    @Query("""
            select l from SceneClassificationLog l
            where l.product.id in :productIds
              and l.createdAt = (
                  select max(l2.createdAt) from SceneClassificationLog l2
                  where l2.product.id = l.product.id)
            """)
    List<SceneClassificationLog> findLatestByProductIds(
            @Param("productIds") Collection<Long> productIds);

    /** FR-11-3 情境判定覆寫率：分子。 */
    long countByOverriddenByIsNotNullAndCreatedAtBetween(Instant from, Instant to);

    /** 分母。 */
    long countByCreatedAtBetween(Instant from, Instant to);
}
