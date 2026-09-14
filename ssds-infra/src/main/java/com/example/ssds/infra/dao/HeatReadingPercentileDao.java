package com.example.ssds.infra.dao;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * §5.3.2「同來源內百分位化」的批次計算。
 *
 * <p>{@link com.example.ssds.infra.entity.HeatReading}、
 * {@code InstagramHeatIngestJob} 兩處的類別/方法註解都提到「同來源內百分位化是
 * 跨品類的批次計算，由既有的百分位化批次任務另外處理」，但專案目前沒有任何
 * Java 檔案做這件事——{@code heat_reading.percentile_within_source} 過去只在
 * dev 種子 SQL（V901/V904）裡被直接寫死。本類別補上這個缺口。
 *
 * <p><b>百分位定義沿用 §5.3.1 已定案的慣例</b>（見
 * {@code V904__apply_category_percentile.sql} 檔頭說明）：採 {@code CUME_DIST()}
 * 而非 {@code PERCENT_RANK()}——後者會讓每個分組最小值固定拿 0，單一樣本分組
 * 更會直接變成 0/0，不符合「百分位等級＝小於等於該值的比例」的統計定義。
 * §5.3.2 原文沒有另外定義 heat_reading 這邊的百分位公式，這裡假設與 §5.3.1
 * 一致；如兩者本來就該不同，需要另外確認。
 *
 * <p>分組鍵是 {@code (source_id, reading_date)}：同一個來源、同一天，
 * 不分關鍵字級或品類級，因為單一來源的 granularity 是固定的
 * （見 {@code heat_source.granularity}），同一 source_id 底下的讀值本來就
 * 全部是同一種目標欄位（keyword_id 或 category_id 其中之一非 null）。
 */
@Repository
public class HeatReadingPercentileDao {

    private final JdbcClient jdbcClient;

    public HeatReadingPercentileDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 重新計算指定日期、所有來源的 percentile_within_source。
     *
     * <p>冪等：可重複執行同一天而不會疊加誤差，因為每次都是用當天全部讀值
     * 重新排名、整批覆寫，不是遞增更新。
     *
     * @return 實際更新的列數
     */
    public int applyPercentiles(LocalDate readingDate) {
        return jdbcClient
                .sql("""
                     WITH ranked AS (
                         SELECT id,
                                CUME_DIST() OVER (
                                    PARTITION BY source_id
                                    ORDER BY raw_value
                                ) * 100 AS pct
                         FROM heat_reading
                         WHERE reading_date = :readingDate
                     )
                     UPDATE heat_reading hr
                     SET percentile_within_source = ROUND(ranked.pct::numeric, 2)
                     FROM ranked
                     WHERE hr.id = ranked.id
                     """)
                .param("readingDate", readingDate)
                .update();
    }
}
