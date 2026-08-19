package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.DecisionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 結案回填（規格書 §7.2 decision_feedback、FR-11-2）。主鍵即 decision_id。 */
@Repository
public interface DecisionFeedbackRepository extends JpaRepository<DecisionFeedback, Long> {

    boolean existsByDecisionId(Long decisionId);
}
