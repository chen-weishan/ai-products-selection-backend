package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.ProductScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductScoreRepository extends JpaRepository<ProductScore, Long> {

        @EntityGraph(attributePaths = { "product", "product.category" })
        Page<ProductScore> findByPeriodAndGradeOrderByFinalScoreDesc(
                        String period, Grade grade, Pageable pageable);

        @EntityGraph(attributePaths = { "product", "product.category" })
        Page<ProductScore> findByPeriodOrderByFinalScoreDesc(String period, Pageable pageable);

        @EntityGraph(attributePaths = { "factors", "weightVersion" })
        Optional<ProductScore> findFirstByProductIdOrderByCalculatedAtDesc(Long productId);

        /** Agent 輸入只採用最新的主情境現行分數，避免把次要情境扣分重複送出。 */
        @EntityGraph(attributePaths = { "factors" })
        Optional<ProductScore> findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(
                        Long productId);

        List<ProductScore> findByProductIdOrderByCalculatedAtDesc(Long productId);

        @Query("""
                        SELECT s FROM ProductScore s
                        WHERE s.period = :period AND s.penaltySubtotal >= 20
                        ORDER BY s.penaltySubtotal DESC
                        """)
        List<ProductScore> findHeavilyPenalized(@Param("period") String period);

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

    @Modifying(flushAutomatically = true)
    @Query("""
            update ProductScore s set s.active = false
            where s.product.id = :productId and s.period = :period and s.active = true
            """)
    int deactivateCurrentScores(
            @Param("productId") Long productId, @Param("period") String period);

    @Modifying
    @Query("""
            update ProductScore s set s.active = false
            where s.product.id = :productId
              and s.period = :period
              and s.sceneType = :sceneType
              and s.active = true
            """)
    int deactivateCurrent(
            @Param("productId") Long productId,
            @Param("period") String period,
            @Param("sceneType") com.example.ssds.core.domain.SceneType sceneType
    );

    /** 品項的評分輸入已改變時，先讓所有現行快照失效。 */
    @Modifying
    @Query("""
            update ProductScore s set s.active = false
            where s.product.id = :productId
              and s.active = true
            """)
    int deactivateAllCurrent(@Param("productId") Long productId);

        /**
         * FR-02 KPI「A 級主推品項數」。AC-02-6：同一品項可同時上多榜，
         * 必須以品項去重；且只計 {@code is_active = true} 的最新有效快照（§5.10），
         * 否則歷史評分會被重複計入。
         */
        @Query("""
                        SELECT COUNT(DISTINCT s.product.id) FROM ProductScore s
                        WHERE s.period = :period AND s.grade = 'A' AND s.active = true
                          AND s.product.trackType = :trackType
                          AND s.product.deletedAt IS NULL
                        """)
        long countAGradeByPeriod(@Param("period") String period, @Param("trackType") TrackType trackType);

        /** FR-02 空狀態判準：該週期是否已有任何有效評分快照。 */
        @Query("""
                        SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END
                        FROM ProductScore s
                        WHERE s.period = :period AND s.active = true
                          AND s.product.trackType = :trackType
                          AND s.product.deletedAt IS NULL
                        """)
        boolean existsByPeriodAndActiveTrue(@Param("period") String period, @Param("trackType") TrackType trackType);

/**
   * FR-02 四榜排行：依情境取 Top N。
   * 必須過濾 {@code is_active = true}，否則同品項的歷史快照會重複上榜（§5.10）。
   */
  @Query("""
          SELECT s FROM ProductScore s
          JOIN FETCH s.product p
          WHERE s.period = :period
            AND s.sceneType = CAST(:sceneType AS string)
            AND s.active = true
            AND p.trackType = :trackType
            AND p.deletedAt IS NULL
          ORDER BY s.finalScore DESC
          """)
  List<ProductScore> findTopByPeriodAndSceneType(
          @Param("period") String period,
          @Param("sceneType") String sceneType,
          @Param("trackType") TrackType trackType,
          Pageable pageable);

  @Modifying
  @Query("""
          DELETE FROM ProductScore s
          WHERE s.period = :period
            AND s.sceneType = :sceneType
            AND s.product.trackType = :trackType
          """)
  void deleteByPeriodAndSceneTypeAndTrackType(@Param("period") String period,
                                              @Param("sceneType") SceneType sceneType,
                                              @Param("trackType") TrackType trackType);
}
