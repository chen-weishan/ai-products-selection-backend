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

        List<ProductScore> findByProductIdOrderByCalculatedAtDesc(Long productId);

        @Query("""
                        SELECT s FROM ProductScore s
                        WHERE s.period = :period AND s.penaltySubtotal >= 20
                        ORDER BY s.penaltySubtotal DESC
                        """)
        List<ProductScore> findHeavilyPenalized(@Param("period") String period);

        long countByPeriodAndConfidenceLessThan(String period, int confidence);

        boolean existsByProductIdAndPeriod(Long productId, String period);

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