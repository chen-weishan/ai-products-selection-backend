package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.Sentiment;
import com.example.ssds.infra.entity.ReviewAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 評論分析結果（規格書 §7.2 review_analysis）。 */
@Repository
public interface ReviewAnalysisRepository extends JpaRepository<ReviewAnalysis, Long> {

    /**
     * 某品項的負評率，review_risk 扣分因子的輸入（§5.2.2）。
     * 回傳 0–1 的比例；完全沒有已分析評論時回傳 null，由呼叫端當作「無資料」處理，
     * 不要當成 0（§5.7 資料不足不懲罰）。
     */
    @Query("""
            select cast(sum(case when a.sentiment = :negative then 1 else 0 end) as double)
                 / cast(count(a) as double)
            from ReviewAnalysis a
            where a.review.product.id = :productId
            """)
    Double findNegativeRatioByProduct(
            @Param("productId") Long productId, @Param("negative") Sentiment negative);

    long countByReviewProductIdAndSentiment(Long productId, Sentiment sentiment);
}
