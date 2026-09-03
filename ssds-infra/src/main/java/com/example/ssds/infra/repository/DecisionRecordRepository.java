package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.TrackType;
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

  @EntityGraph(attributePaths = { "product", "score", "decidedBy" })
  Page<DecisionRecord> findByDecidedAtBetween(Instant from, Instant to, Pageable pageable);

  List<DecisionRecord> findByProductIdOrderByDecidedAtDesc(Long productId);

  @EntityGraph(attributePaths = { "product" })
  Page<DecisionRecord> findByDecision(DecisionType decision, Pageable pageable);



@Query("""
          SELECT dr FROM DecisionRecord dr
          JOIN FETCH dr.product p
          WHERE dr.decision = :decision
            AND dr.campaignEndDate IS NOT NULL
            AND dr.campaignEndDate <= :cutoff
            AND NOT EXISTS (SELECT cr FROM CampaignResult cr WHERE cr.decision = dr)
            AND p.deletedAt IS NULL
          ORDER BY dr.campaignEndDate ASC
          """)
List<DecisionRecord> findOverdueCampaigns(
           @Param("decision") DecisionType decision,
           @Param("cutoff") LocalDate cutoff);

@Query("""
          SELECT COUNT(dr) FROM DecisionRecord dr
          JOIN dr.product p
          WHERE dr.decision = :decision
            AND dr.campaignEndDate IS NOT NULL
            AND dr.campaignEndDate <= :cutoff
            AND NOT EXISTS (SELECT cr FROM CampaignResult cr WHERE cr.decision = dr)
            AND p.deletedAt IS NULL
            AND p.trackType = :trackType
          """)
long countOverdueCampaigns(
           @Param("decision") DecisionType decision,
           @Param("cutoff") LocalDate cutoff,
           @Param("trackType") TrackType trackType);

    /** FR-11-3：情境判定覆寫率與 AI 採納率的分母。 */
    long countByDecidedAtBetween(Instant from, Instant to);

  long countByFollowedAiFalseAndDecidedAtBetween(Instant from, Instant to);
}
