package com.example.ssds.infra.dao;

import com.example.ssds.infra.dao.projection.AccuracyRow;
import com.example.ssds.infra.dao.projection.CategorySalesRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 準確度分析與統計彙總（FR-11-3、FR-12、FR-15）。
 *
 * <p>這些查詢清一色是聚合運算，把資料搬到 JVM 再算既慢又佔記憶體；
 * 交給 PostgreSQL 的 {@code corr()}、{@code avg()} 直接在資料端完成。
 */
@Repository
public class AccuracyAnalysisDao {

    private final JdbcClient jdbcClient;

    public AccuracyAnalysisDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * FR-11-3 準確度分析。
     *
     * <p>四項指標一次算完，因為畫面是一起顯示的，分四支查詢等於掃四次相同的表。
     * {@code sampleSize} 是其餘三項的效度前提（AC-11-5：未達 200 筆須顯示警示），
     * 所以即使其他值為 null 也一定會回傳。
     */
    public AccuracyRow findAccuracy(Instant from, Instant to) {
        return jdbcClient
                .sql("""
                     WITH paired AS (
                         SELECT cs.score,
                                cs.grade,
                                cs.scene_overridden,
                                d.followed_ai,
                                cr.actual_qty,
                                d.first_order_qty
                         FROM decision_record d
                                  JOIN campaign_snapshot cs ON cs.decision_id = d.id
                                  JOIN campaign_result   cr ON cr.decision_id = d.id
                         WHERE d.decided_at BETWEEN :from AND :to
                     )
                     SELECT COUNT(*)                                  AS sample_size,
                            CORR(score, actual_qty)                   AS score_qty_correlation,
                            AVG(CASE WHEN grade = 'A'
                                     THEN CASE WHEN first_order_qty IS NOT NULL
                                                 AND actual_qty >= first_order_qty
                                               THEN 1.0 ELSE 0.0 END
                                END)                                  AS grade_a_hit_rate,
                            AVG(CASE WHEN scene_overridden THEN 1.0 ELSE 0.0 END)
                                                                      AS scene_override_rate,
                            AVG(CASE WHEN followed_ai THEN 1.0 ELSE 0.0 END)
                                                                      AS ai_follow_rate
                     FROM paired
                     """)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query((rs, rowNum) -> new AccuracyRow(
                        rs.getLong("sample_size"),
                        (Double) rs.getObject("score_qty_correlation"),
                        toDouble(rs.getObject("grade_a_hit_rate")),
                        toDouble(rs.getObject("scene_override_rate")),
                        toDouble(rs.getObject("ai_follow_rate"))))
                .single();
    }

    /**
     * FR-15 統計模組：各因子與實際銷量的皮爾森相關係數。
     *
     * <p>AC-15-2 明訂建議權重必須由統計迴歸產生，AI 只負責解讀 ——
     * 所以這個數字的唯一來源就是這支查詢，不經過任何模型。
     *
     * <p>因子值取自 campaign_snapshot.factor_values 的凍結快照而非 score_factor：
     * 後者會隨重新評分而變動，用它做迴歸等於拿今天的因子去解釋當初的結果。
     *
     * @return factorCode 對相關係數；樣本不足以計算相關時該因子的值為 null
     */
    public Map<String, Double> findFactorCorrelations(Instant from, Instant to) {
        return jdbcClient
                .sql("""
                     WITH exploded AS (
                         SELECT f.key                                    AS factor_code,
                                (f.value ->> 'norm')::numeric            AS normalized_value,
                                cr.actual_qty
                         FROM decision_record d
                                  JOIN campaign_snapshot cs ON cs.decision_id = d.id
                                  JOIN campaign_result   cr ON cr.decision_id = d.id
                                  CROSS JOIN LATERAL jsonb_each(cs.factor_values) AS f(key, value)
                         WHERE d.decided_at BETWEEN :from AND :to
                           -- 必須判「值不是 null」而不是 jsonb 的 ? 運算子。
                           -- 快照連扣分因子一起凍結，而扣分列的 norm 鍵是存在的、
                           -- 只是值為 null；用 ? 會把 REVIEW_RISK 這類扣分因子
                           -- 也送進迴歸，算出一整排 null 相關係數。
                           AND (f.value ->> 'norm') IS NOT NULL
                     )
                     SELECT factor_code, CORR(normalized_value, actual_qty) AS correlation
                     FROM exploded
                     GROUP BY factor_code
                     ORDER BY factor_code
                     """)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query()
                .listOfRows()
                .stream()
                .collect(java.util.HashMap::new,
                        (m, row) -> m.put((String) row.get("factor_code"),
                                toDouble(row.get("correlation"))),
                        java.util.HashMap::putAll);
    }

    /**
     * 類別銷售彙總（FR-12 報表）。
     *
     * <p>同時是 conversion 因子的同品類基準：§5.3.1 要在同品類內做百分位，
     * 得先知道同品類整體長什麼樣。走 idx_sales_date_cat(order_date, category_id)。
     */
    public List<CategorySalesRow> findCategorySales(LocalDate from, LocalDate to) {
        return jdbcClient
                .sql("""
                     SELECT c.id                                   AS category_id,
                            c.name                                 AS category_name,
                            COALESCE(SUM(s.qty), 0)                AS total_qty,
                            COALESCE(SUM(s.price * s.qty), 0)      AS total_revenue,
                            SUM(s.impression)                      AS total_impression,
                            CASE WHEN COALESCE(SUM(s.impression), 0) = 0 THEN NULL
                                 ELSE SUM(s.qty)::float8 / SUM(s.impression)::float8
                            END                                    AS conversion_rate
                     FROM sales_record s
                              JOIN category c ON c.id = s.category_id
                     WHERE s.order_date BETWEEN :from AND :to
                     GROUP BY c.id, c.name
                     ORDER BY total_revenue DESC
                     """)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new CategorySalesRow(
                        rs.getLong("category_id"),
                        rs.getString("category_name"),
                        rs.getLong("total_qty"),
                        rs.getBigDecimal("total_revenue"),
                        (Long) rs.getObject("total_impression"),
                        (Double) rs.getObject("conversion_rate")))
                .list();
    }

    /**
     * §5.3.1 同品類百分位。
     *
     * <p>直接用 PostgreSQL 的 {@code percent_rank()} 視窗函數，
     * 不必把整個品類的值拉回 JVM 排序 —— 品類一大就會變成明顯的瓶頸。
     *
     * @return 0–100 的百分位；該品類無其他樣本時回傳 null
     */
    public Double findMarginPercentileWithinCategory(Long productId, Long categoryId) {
        return jdbcClient
                .sql("""
                     WITH ranked AS (
                         SELECT id,
                                PERCENT_RANK() OVER (ORDER BY margin_rate) * 100 AS pct
                         FROM product
                         WHERE category_id = :categoryId
                           AND margin_rate IS NOT NULL
                           AND track_type = 'A'
                     )
                     SELECT pct FROM ranked WHERE id = :productId
                     """)
                .param("productId", productId)
                .param("categoryId", categoryId)
                .query(Double.class)
                .optional()
                .orElse(null);
    }

    /**
     * §5.3.1：同品類樣本數低於 10 時退回全品類百分位，並降低信心度。
     * 呼叫端用這個計數決定走哪一條路。
     */
    public long countScorableInCategory(Long categoryId) {
        return jdbcClient
                .sql("""
                     SELECT COUNT(*) FROM product
                     WHERE category_id = :categoryId
                       AND track_type = 'A'
                       AND status NOT IN ('DRAFT', 'REJECTED')
                     """)
                .param("categoryId", categoryId)
                .query(Long.class)
                .single();
    }

    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
