package com.example.ssds.infra.repository;

// import com.example.ssds.core.domain.KeywordLifecycle; // ⚠️ 暫時註解掉，避免找不到類別的編譯錯誤
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.core.dto.TrendChartProjection;
import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.core.dto.TrendCompositeProjection;
import com.example.ssds.core.dto.TrendSourceDetailProjection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 關鍵字查詢（規格書 §7.2 trend_keyword、FR-06）。 */
@Repository
public interface TrendKeywordRepository extends JpaRepository<TrendKeyword, Long> {

    Optional<TrendKeyword> findByKeyword(String keyword);

    /** 每日 06:00 熱度採集的取件範圍（§5.10）。 */
    List<TrendKeyword> findByEnabledTrue();

    // List<TrendKeyword> findByLifecycle(KeywordLifecycle lifecycle); // ⚠️ 暫時註解掉


    // 1. 取得所有趨勢訊號 (總覽用)

    @Query(value = """
        WITH DailyComposite AS (
            SELECT 
                hr.keyword_id,
                hr.reading_date,
                SUM(hr.percentile_within_source * hs.composite_weight) AS composite_heat
            FROM heat_reading hr
            JOIN heat_source hs ON hr.source_id = hs.id
            WHERE hs.enabled = TRUE 
              AND hs.availability = 'AVAILABLE'
            GROUP BY hr.keyword_id, hr.reading_date
        ),
        SlopeCalculation AS (
            SELECT 
                t.keyword_id,
                t.composite_heat AS heat_today,
                (t.composite_heat - t7.composite_heat) / GREATEST(t7.composite_heat, 0.01) AS slope_7d,
                (t.composite_heat - t30.composite_heat) / GREATEST(t30.composite_heat, 0.01) AS slope_30d
            FROM DailyComposite t
            LEFT JOIN DailyComposite t7 
                ON t.keyword_id = t7.keyword_id AND t7.reading_date = t.reading_date - INTERVAL '7 days'
            LEFT JOIN DailyComposite t30 
                ON t.keyword_id = t30.keyword_id AND t30.reading_date = t.reading_date - INTERVAL '30 days'
            WHERE t.reading_date = (SELECT MAX(reading_date) FROM heat_reading)
        )
        SELECT 
            tk.id AS keywordId,
            tk.keyword AS keyword,
            ROUND(sc.heat_today, 2) AS heatToday,
            ROUND(sc.slope_7d * 100, 2) AS slope7d,
            ROUND(sc.slope_30d * 100, 2) AS slope30d,
            CASE 
                WHEN sc.slope_7d < 0 AND sc.slope_30d > 0 THEN '⚠️ 可能見頂'
                WHEN sc.slope_7d > 0 AND sc.slope_30d < 0 THEN '🔥 觸底反彈'
                WHEN sc.slope_7d > 0 AND sc.slope_30d > 0 THEN '🚀 持續上升'
                ELSE '📉 持續衰退'
            END AS aiSignal
        FROM SlopeCalculation sc
        JOIN trend_keyword tk ON sc.keyword_id = tk.id
    """, nativeQuery = true)
    List<TrendSignalProjection> findTrendSignals();


    // 2. FR-06 90 天歷史熱度折線圖查詢

    @Query(value = """
        WITH DailyComposite AS (
            SELECT 
                hr.keyword_id,
                hr.reading_date,
                SUM(hr.percentile_within_source * hs.composite_weight) AS composite_heat
            FROM heat_reading hr
            JOIN heat_source hs ON hr.source_id = hs.id
            WHERE hs.enabled = TRUE 
              AND hs.availability = 'AVAILABLE'
            GROUP BY hr.keyword_id, hr.reading_date
        )
        SELECT 
            reading_date AS date,
            ROUND(composite_heat, 2) AS heatScore
        FROM DailyComposite
        WHERE keyword_id = :keywordId
          AND reading_date >= CURRENT_DATE - INTERVAL '90 days'
        ORDER BY reading_date ASC
    """, nativeQuery = true)
    List<TrendChartProjection> findTrendChartByKeywordId(@Param("keywordId") Long keywordId);


    // 3. FR-06 單一關鍵字：各來源明細（AC-06-1，各來源分列，不合併）
@Query(value = """
    WITH SourceDaily AS (
        SELECT 
            hr.source_id,
            hs.source_code,
            hs.composite_weight AS original_weight,
            hs.availability,
            hr.reading_date,
            hr.percentile_within_source
        FROM heat_reading hr
        JOIN heat_source hs ON hr.source_id = hs.id
        WHERE hr.keyword_id = :keywordId
          AND hs.enabled = TRUE
    ),
    Today AS (
        SELECT source_id, source_code, original_weight, availability, percentile_within_source AS today_pct
        FROM SourceDaily
        WHERE reading_date = (SELECT MAX(reading_date) FROM SourceDaily)
    ),
    D7 AS (
        SELECT source_id, percentile_within_source AS pct_7d
        FROM SourceDaily
        WHERE reading_date = (SELECT MAX(reading_date) FROM SourceDaily) - INTERVAL '7 days'
    ),
    D30 AS (
        SELECT source_id, percentile_within_source AS pct_30d
        FROM SourceDaily
        WHERE reading_date = (SELECT MAX(reading_date) FROM SourceDaily) - INTERVAL '30 days'
    )
    SELECT
        t.source_code AS sourceName,
        ROUND(t.today_pct, 2) AS percentile,
        t.original_weight AS originalWeight,
        CASE WHEN t.availability = 'AVAILABLE' THEN t.original_weight ELSE 0 END AS rawActualWeight,
        t.availability AS status,
        ROUND((t.today_pct - COALESCE(d7.pct_7d, 0.01)) / GREATEST(COALESCE(d7.pct_7d, 0.01), 0.01) * 100, 2) AS slope7d,
        ROUND((t.today_pct - COALESCE(d30.pct_30d, 0.01)) / GREATEST(COALESCE(d30.pct_30d, 0.01), 0.01) * 100, 2) AS slope30d
    FROM Today t
    LEFT JOIN D7 d7 ON t.source_id = d7.source_id
    LEFT JOIN D30 d30 ON t.source_id = d30.source_id
""", nativeQuery = true)
List<TrendSourceDetailProjection> findSourceDetailsByKeywordId(@Param("keywordId") Long keywordId);


// 4. FR-06 單一關鍵字：合成後整體今日/7日/30日

    @Query(value = """
        WITH DailyComposite AS (
            SELECT 
                hr.keyword_id,
                hr.reading_date,
                SUM(hr.percentile_within_source * hs.composite_weight) AS composite_heat
            FROM heat_reading hr
            JOIN heat_source hs ON hr.source_id = hs.id
            WHERE hs.enabled = TRUE 
              AND hs.availability = 'AVAILABLE'
              AND hr.keyword_id = :keywordId
            GROUP BY hr.keyword_id, hr.reading_date
        )
        SELECT 
            t.composite_heat AS heatToday,
            ROUND((t.composite_heat - t7.composite_heat) / GREATEST(t7.composite_heat, 0.01) * 100, 2) AS slope7d,
            ROUND((t.composite_heat - t30.composite_heat) / GREATEST(t30.composite_heat, 0.01) * 100, 2) AS slope30d
        FROM DailyComposite t
        LEFT JOIN DailyComposite t7 
            ON t7.reading_date = t.reading_date - INTERVAL '7 days'
        LEFT JOIN DailyComposite t30 
            ON t30.reading_date = t.reading_date - INTERVAL '30 days'
        WHERE t.reading_date = (SELECT MAX(reading_date) FROM heat_reading WHERE keyword_id = :keywordId)
    """, nativeQuery = true)
    TrendCompositeProjection findCompositeByKeywordId(@Param("keywordId") Long keywordId);
}
