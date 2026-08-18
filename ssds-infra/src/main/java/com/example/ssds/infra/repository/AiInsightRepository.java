package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.InsightType;
import com.example.ssds.infra.entity.AiInsight;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** AI 洞察（規格書 §7.2 ai_insight、FR-05 區塊 F）。 */
@Repository
public interface AiInsightRepository extends JpaRepository<AiInsight, Long> {

    Optional<AiInsight> findByProductIdAndInsightTypeAndCurrentTrue(
            Long productId, InsightType insightType);

    List<AiInsight> findByProductIdAndCurrentTrue(Long productId);

    /** 歷史版本，供比較不同模型或 prompt 版本的輸出差異。 */
    List<AiInsight> findByProductIdAndInsightTypeOrderByGeneratedAtDesc(
            Long productId, InsightType insightType);

    /**
     * 產生新版本前，先把同 product + type 的舊列下架。
     * 必須先做這一步再插入新列，否則會撞上 partial unique index。
     */
    @Modifying
    @Query("""
            update AiInsight i set i.current = false
            where i.product.id = :productId and i.insightType = :insightType and i.current = true
            """)
    int demoteCurrent(
            @Param("productId") Long productId, @Param("insightType") InsightType insightType);

    /** AC-07-3：相同請求 7 日內命中快取，不重複計費。 */
    @Query("""
            select i from AiInsight i
            where i.product.id = :productId and i.insightType = :insightType
              and i.generatedAt >= :since and i.current = true
            """)
    Optional<AiInsight> findCached(
            @Param("productId") Long productId,
            @Param("insightType") InsightType insightType,
            @Param("since") Instant since);
}
