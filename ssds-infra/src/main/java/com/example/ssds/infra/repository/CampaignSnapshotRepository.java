package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.CampaignSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * 開團快照（規格書 §7.2.8 campaign_snapshot）。與決策 1:1，主鍵即 decision_id。
 *
 * <p>快照本身已不存分數、分級與情境（v3.0 精簡為六欄），
 * 那些一律 join 回 product_score 取得——本 repository 的查詢也照這條路走。
 */
@Repository
public interface CampaignSnapshotRepository extends JpaRepository<CampaignSnapshot, Long> {

    Optional<CampaignSnapshot> findByDecisionId(Long decisionId);

    /** FR-11-3 覆寫率統計。 */
    long countBySceneOverriddenTrue();

    /**
     * 依情境查快照。情境存在 product_score 上，不在本表，
     * 所以必須經 decision → score 兩層 join。
     */
    @Query("""
           select s from CampaignSnapshot s
           where s.decision.score.sceneType = :sceneType
           """)
    List<CampaignSnapshot> findBySceneType(SceneType sceneType);
}
