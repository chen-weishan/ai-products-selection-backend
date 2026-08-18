package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.CampaignSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 開團快照（規格書 §7.2 campaign_snapshot）。與決策 1:1。 */
@Repository
public interface CampaignSnapshotRepository extends JpaRepository<CampaignSnapshot, Long> {

    Optional<CampaignSnapshot> findByDecisionId(Long decisionId);

    /** FR-11-3 覆寫率統計。 */
    long countBySceneOverriddenTrue();

    List<CampaignSnapshot> findBySceneType(SceneType sceneType);
}
