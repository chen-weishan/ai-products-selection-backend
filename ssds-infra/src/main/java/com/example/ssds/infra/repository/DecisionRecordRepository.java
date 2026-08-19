package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.infra.entity.DecisionRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 採購決策（規格書 §7.2 decision_record、FR-11）。 */
@Repository
public interface DecisionRecordRepository extends JpaRepository<DecisionRecord, Long> {

    @EntityGraph(attributePaths = {"product", "score", "decidedBy"})
    Page<DecisionRecord> findByDecidedAtBetween(Instant from, Instant to, Pageable pageable);

    List<DecisionRecord> findByProductIdOrderByDecidedAtDesc(Long productId);

    @EntityGraph(attributePaths = {"product"})
    Page<DecisionRecord> findByDecision(DecisionType decision, Pageable pageable);

    /**
     * AC-11-3：結案滿 7 天仍未回填 campaign_result 者，於儀表板待辦區提示。
     * 用 left join + is null 而非 not exists，讓查詢計畫可以走 hash anti-join。
     */
    @Query("""
            select d from DecisionRecord d
            left join CampaignResult r on r.decision = d
            where d.decision = com.example.ssds.core.domain.DecisionType.ADOPT
              and r.id is null
              and d.expectedListDate is not null
              and d.expectedListDate <= :cutoff
            order by d.expectedListDate
            """)
    List<DecisionRecord> findPendingFeedback(@Param("cutoff") LocalDate cutoff);

    /** FR-11-3：情境判定覆寫率與 AI 採納率的分母。 */
    long countByDecidedAtBetween(Instant from, Instant to);

    long countByFollowedAiFalseAndDecidedAtBetween(Instant from, Instant to);
}
