package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
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

    @Query(value = """
            select s from ProductScore s
            join fetch s.product p
            left join fetch p.category
            where s.period = :period
              and s.active = true
              and p.deletedAt is null
              and (:scene is null or s.sceneType = :scene)
              and (:categoryId is null or p.category.id = :categoryId)
            order by s.finalScore desc, s.id asc
            """,
            countQuery = """
            select count(s) from ProductScore s
            join s.product p
            where s.period = :period
              and s.active = true
              and p.deletedAt is null
              and (:scene is null or s.sceneType = :scene)
              and (:categoryId is null or p.category.id = :categoryId)
            """)
    Page<ProductScore> findRanking(
            @Param("period") String period,
            @Param("scene") SceneType scene,
            @Param("categoryId") Long categoryId,
            Pageable pageable);

    /**
     * FR-04 單一品項的分數快照（規格書 §8.2 GET /products/{id}/scores）。
     *
     * <p>{@code scene} 的語意與 {@link #findRanking} <b>不同</b>：
     * 那支為 null 是「不篩榜」，這支為 null 是「<b>取主情境那筆</b>」（§8.2：
     * 「省略 scene 回傳主情境」）。兩個條件互斥，剛好各自在對方為 null 時失效：
     * <ul>
     *   <li>{@code (:scene is null or s.sceneType = :scene)}——有給就依榜篩</li>
     *   <li>{@code (:scene is not null or s.primary = true)}——沒給就取主情境</li>
     * </ul>
     *
     * <p>{@code s.active = true} 不可省：§5.10 重算不覆寫，同一組
     * (product, period, sceneType) 可能有多列歷史，不篩會拿到舊分數。
     *
     * <p>factors 是 ToMany，不在這裡 fetch——由呼叫端用
     * {@code ScoreFactorRepository.findByScoreId(...)} 另外取。
     */
    @Query("""
            select s from ProductScore s
            join fetch s.product p
            left join fetch p.category
            where p.id = :productId
              and s.period = :period
              and s.active = true
              and p.deletedAt is null
              and (:scene is null or s.sceneType = :scene)
              and (:scene is not null or s.primary = true)
            """)
    Optional<ProductScore> findSnapshot(
            @Param("productId") Long productId,
            @Param("period") String period,
            @Param("scene") SceneType scene);
}
