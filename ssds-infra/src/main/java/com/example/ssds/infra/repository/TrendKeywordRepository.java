package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.core.dto.TrendSignalProjection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TrendKeywordRepository extends JpaRepository<TrendKeyword, Long> {

    Optional<TrendKeyword> findByKeyword(String keyword);

    /** 每日 06:00 熱度採集的取件範圍（§5.10）。 */
    List<TrendKeyword> findByEnabledTrue();

    // 取得所有趨勢訊號（總覽用，GET /trends）
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
}