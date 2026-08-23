package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.CampaignResult;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 開團結果（規格書 §7.2 campaign_result）。
 *
 * <p>FR-15 的統計迴歸以本表為標籤資料，{@link #count()} 就是
 * AC-15-1 效度警示所看的樣本數。
 */
@Repository
public interface CampaignResultRepository extends JpaRepository<CampaignResult, Long> {

    /** 主鍵即 decision_id，等同 findById；保留這個名稱是為了呼叫端讀起來清楚。 */
    Optional<CampaignResult> findByDecisionId(Long decisionId);

    long countBySelloutStatus(SelloutStatus selloutStatus);
}
