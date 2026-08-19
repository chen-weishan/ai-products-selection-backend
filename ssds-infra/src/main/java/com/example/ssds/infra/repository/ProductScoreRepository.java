package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.infra.entity.ProductScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 選品分數（規格書 §7.2 product_score、FR-04）。
 *
 * <p>§5.10：每次評分產生新列、不覆寫，所以「目前分數」一律是
 * 「該品項最新一筆」，查詢時務必帶排序，不能只 findByProductId。
 */
@Repository
public interface ProductScoreRepository extends JpaRepository<ProductScore, Long> {

    /** FR-04 排行榜，走 idx_score_period_grade(period, grade, final_score DESC)。 */
    @EntityGraph(attributePaths = {"product", "product.category"})
    Page<ProductScore> findByPeriodAndGradeOrderByFinalScoreDesc(
            String period, Grade grade, Pageable pageable);

    @EntityGraph(attributePaths = {"product", "product.category"})
    Page<ProductScore> findByPeriodOrderByFinalScoreDesc(String period, Pageable pageable);

    /** 品項詳情的現行分數。 */
    @EntityGraph(attributePaths = {"factors", "weightVersion"})
    Optional<ProductScore> findFirstByProductIdOrderByCalculatedAtDesc(Long productId);

    /** FR-05 分數走勢：同一品項的歷史分數。 */
    List<ProductScore> findByProductIdOrderByCalculatedAtDesc(Long productId);

    /** §5.6 硬規則：扣分達 20 以上者強制進入風險示警清單。 */
    @Query("""
            select s from ProductScore s
            where s.period = :period and s.penaltySubtotal >= 20
            order by s.penaltySubtotal desc
            """)
    List<ProductScore> findHeavilyPenalized(@Param("period") String period);

    /** §5.9：信心度低於 50 的分數，儀表板需標示。 */
    long countByPeriodAndConfidenceLessThan(String period, int confidence);

    boolean existsByProductIdAndPeriod(Long productId, String period);
}
