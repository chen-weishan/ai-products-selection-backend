package com.example.ssds.infra.dao;

import com.example.ssds.infra.dao.projection.TrendPointRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 趨勢與熱度查詢（FR-06、§5.3）。
 *
 * <p>§7.3：heat_composite_daily 的主鍵 (keyword_id, stat_date) 就是查詢鍵，
 * 90 日區間查詢為索引範圍掃描。
 *
 * <p>v1.0 的 trend_daily（單來源 0–100 熱度）已於 v3.0 廢除，職責拆給
 * heat_reading（各來源原始值）與 heat_composite_daily（合成值）。
 * 曲線、斜率與階段判定一律只認後者（§7.2.3）。
 */
@Repository
public class TrendQueryDao {

    private final JdbcClient jdbcClient;

    public TrendQueryDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** FR-06 趨勢折線：一次取多個關鍵字的區間資料。 */
    public List<TrendPointRow> findTrendRange(List<Long> keywordIds, LocalDate from, LocalDate to) {
        if (keywordIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient
                .sql("""
                     SELECT d.keyword_id, k.keyword, d.stat_date, d.composite_value
                     FROM heat_composite_daily d
                              JOIN trend_keyword k ON k.id = d.keyword_id
                     WHERE d.keyword_id IN (:keywordIds)
                       AND d.stat_date BETWEEN :from AND :to
                     ORDER BY d.keyword_id, d.stat_date
                     """)
                .param("keywordIds", keywordIds)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new TrendPointRow(
                        rs.getLong("keyword_id"),
                        rs.getString("keyword"),
                        rs.getDate("stat_date").toLocalDate(),
                        rs.getBigDecimal("composite_value")))
                .list();
    }

    /**
     * §5.3.3 斜率計算所需的三個觀測點：t、t−7、t−30。
     *
     * <pre>
     *   slope_7d  = (heat_t − heat_{t-7})  / max(heat_{t-7}, ε)
     *   slope_30d = (heat_t − heat_{t-30}) / max(heat_{t-30}, ε)
     * </pre>
     *
     * <p>刻意只回傳原始觀測值、不在 SQL 算斜率：ε 的取值與「兩者背離時標記
     * 可能見頂」的判斷屬於評分規則，該由 ssds-core 的計分引擎決定，
     * 散在 SQL 裡日後沒人找得到。
     *
     * <p>註：{@code heat_composite_daily} 本身也有 slope_7d／slope_30d 兩欄，
     * 那是批次寫入時算好的結果值。本方法給的是「現在重算一次」用的觀測點，
     * 兩者用途不同——要顯示既有結果就直接讀那兩欄，不必呼叫這裡。
     *
     * @return key 為 {@code "t"} / {@code "t7"} / {@code "t30"}，缺該日資料時不含該 key
     */
    public Map<String, BigDecimal> findSlopeAnchors(Long keywordId, LocalDate asOf) {
        return jdbcClient
                .sql("""
                     SELECT CASE stat_date
                                WHEN :t   THEN 't'
                                WHEN :t7  THEN 't7'
                                ELSE 't30'
                            END AS anchor,
                            composite_value
                     FROM heat_composite_daily
                     WHERE keyword_id = :keywordId
                       AND stat_date IN (:t, :t7, :t30)
                     """)
                .param("keywordId", keywordId)
                .param("t", asOf)
                .param("t7", asOf.minusDays(7))
                .param("t30", asOf.minusDays(30))
                .query()
                .listOfRows()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row.get("anchor"),
                        row -> (BigDecimal) row.get("composite_value")));
    }

    /**
     * §5.3.2 熱度多來源合成。
     *
     * <p>先在各來源內百分位化、再依合成權重加總；此處只做「可用來源的加權平均」，
     * 權重的重新正規化（分母只算可用來源）也一併在 SQL 內完成，
     * 因此任一來源不可用時其餘來源會自動按比例補上（§5.7 優雅降級）。
     *
     * <p>回傳 null 表示該關鍵字當日沒有任何可用來源的讀值 ——
     * 呼叫端要當成「無資料」而非 0（§5.7 資料不足不懲罰）。
     */
    public Double findCompositeHeat(Long keywordId, LocalDate readingDate) {
        return jdbcClient
                .sql("""
                     SELECT CASE WHEN SUM(hs.composite_weight) = 0 THEN NULL
                                 ELSE SUM(hr.percentile_within_source * hs.composite_weight)
                                      / SUM(hs.composite_weight)
                            END AS composite
                     FROM heat_reading hr
                              JOIN heat_source hs ON hs.id = hr.source_id
                     WHERE hr.keyword_id = :keywordId
                       AND hr.reading_date = :readingDate
                       AND hr.percentile_within_source IS NOT NULL
                       AND hs.enabled = TRUE
                       AND hs.availability <> 'UNAVAILABLE'
                     """)
                .param("keywordId", keywordId)
                .param("readingDate", readingDate)
                .query(Double.class)
                .optional()
                .orElse(null);
    }

    /**
     * §5.3.2 的人工標記合成值：加權平均熱度等級，權重為時間衰減係數。
     *
     * <p>衰減規則（AC-14-2）：14 天內 ×1.0、14–30 天 ×0.5、30 天後 ×0。
     * 這裡把衰減寫進 SQL 是刻意的 —— 它是「資料的有效性」而非評分規則，
     * 讓資料庫直接濾掉失效標記，可以少搬一堆已經不算數的列。
     *
     * <p>回傳 null 表示沒有仍有效的標記。
     */
    public Double findManualHeatScore(Long productId, Long keywordId, LocalDate asOf) {
        return jdbcClient
                .sql("""
                     SELECT CASE WHEN SUM(w.decay) = 0 THEN NULL
                                 ELSE SUM(w.heat_level * w.decay) / SUM(w.decay)
                            END AS manual_heat
                     FROM (SELECT t.heat_level,
                                  CASE
                                      WHEN :asOf - t.observed_at::date >= 30 THEN 0.0
                                      WHEN :asOf - t.observed_at::date >= 14 THEN 0.5
                                      ELSE 1.0
                                  END AS decay
                           FROM manual_heat_tag t
                           WHERE (:productId::bigint IS NOT NULL AND t.product_id = :productId)
                              OR (:keywordId::bigint IS NOT NULL AND t.keyword_id = :keywordId)) w
                     """)
                .param("productId", productId)
                .param("keywordId", keywordId)
                .param("asOf", asOf)
                .query(Double.class)
                .optional()
                .orElse(null);
    }

    /**
     * §5.3.2 的信心係數：依「標記人數」而非標記筆數 ——
     * 同一個人連貼五則不會比較可信。1 人 0.6、2 人 0.8、3 人以上 1.0。
     */
    public double findManualConfidenceCoefficient(Long productId, Long keywordId, LocalDate asOf) {
        Long taggers = jdbcClient
                .sql("""
                     SELECT COUNT(DISTINCT tagged_by)
                     FROM manual_heat_tag
                     WHERE :asOf - observed_at::date < 30
                       AND ((:productId::bigint IS NOT NULL AND product_id = :productId)
                         OR (:keywordId::bigint IS NOT NULL AND keyword_id = :keywordId))
                     """)
                .param("productId", productId)
                .param("keywordId", keywordId)
                .param("asOf", asOf)
                .query(Long.class)
                .single();

        if (taggers >= 3) {
            return 1.0;
        }
        return switch (taggers.intValue()) {
            case 2 -> 0.8;
            case 1 -> 0.6;
            default -> 0.0;
        };
    }
}
