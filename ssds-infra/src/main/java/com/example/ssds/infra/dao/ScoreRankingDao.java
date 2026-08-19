package com.example.ssds.infra.dao;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.dao.projection.FactorBreakdownRow;
import com.example.ssds.infra.dao.projection.ScoreRankingRow;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 選品分數排行與因子明細的讀取（FR-04、FR-05）。
 *
 * <p><b>為什麼這裡不用 JPA</b>：排行頁一次要顯示品項名、類別名、分數、分級、
 * 信心度，全部來自不同表。用 Entity 查回來之後再走關聯，就是 §7.3 點名禁止的
 * N+1；即使掛滿 {@code @EntityGraph}，Hibernate 為了組出物件圖仍會產生
 * 比實際需要更寬的 SQL。這類「唯讀、扁平、給畫面看」的查詢，
 * 直接寫 SQL 搭配 record projection 最省也最好讀。
 *
 * <p>寫入路徑仍然走 JPA Repository —— DAO 只負責讀。
 */
@Repository
public class ScoreRankingDao {

    private final JdbcClient jdbcClient;

    public ScoreRankingDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static final String RANKING_SELECT =
            """
            SELECT s.id            AS score_id,
                   p.id            AS product_id,
                   p.name          AS product_name,
                   c.id            AS category_id,
                   c.name          AS category_name,
                   s.scene_type,
                   s.bonus_subtotal,
                   s.penalty_subtotal,
                   s.final_score,
                   s.grade,
                   s.confidence
            FROM product_score s
                     JOIN product  p ON p.id = s.product_id
                     JOIN category c ON c.id = p.category_id
            """;

    /**
     * 某期別的排行榜。走 idx_score_period_grade(period, grade, final_score DESC)，
     * 因此 ORDER BY 的方向必須與索引一致，否則 PostgreSQL 得回頭排序。
     *
     * @param grade null 表不分級，回傳全部
     */
    public List<ScoreRankingRow> findRanking(String period, Grade grade, int limit, int offset) {
        String sql = RANKING_SELECT
                + " WHERE s.period = :period "
                + (grade == null ? "" : " AND s.grade = :grade ")
                + " ORDER BY s.final_score DESC, p.name ASC LIMIT :limit OFFSET :offset";

        var spec = jdbcClient.sql(sql)
                .param("period", period)
                .param("limit", limit)
                .param("offset", offset);
        if (grade != null) {
            spec = spec.param("grade", grade.name());
        }
        return spec.query(ScoreRankingDao::mapRanking).list();
    }

    /** 情境別排行（FR-04：話題爆款榜與常態補貨榜的分數分佈不同，需分開看）。 */
    public List<ScoreRankingRow> findRankingByScene(String period, SceneType sceneType, int limit) {
        return jdbcClient
                .sql(RANKING_SELECT
                        + " WHERE s.period = :period AND s.scene_type = :sceneType "
                        + " ORDER BY s.final_score DESC LIMIT :limit")
                .param("period", period)
                .param("sceneType", sceneType.name())
                .param("limit", limit)
                .query(ScoreRankingDao::mapRanking)
                .list();
    }

    /**
     * 風險示警清單的來源之一（AC-10-4）：扣分達 20 分以上者必定列入。
     * 門檻寫在 SQL 而非 Java，是為了讓資料庫能只掃需要的列。
     */
    public List<ScoreRankingRow> findHeavilyPenalized(String period) {
        return jdbcClient
                .sql(RANKING_SELECT
                        + " WHERE s.period = :period AND s.penalty_subtotal >= 20 "
                        + " ORDER BY s.penalty_subtotal DESC")
                .param("period", period)
                .query(ScoreRankingDao::mapRanking)
                .list();
    }

    /**
     * 單筆分數的因子明細（FR-05 區塊 C／D）。
     *
     * <p>加分列排前、扣分列排後，與畫面區塊順序一致；
     * contribution 由 SQL 算好，避免前後端各算一次而小數處理不同。
     */
    public List<FactorBreakdownRow> findFactorBreakdown(Long scoreId) {
        return jdbcClient
                .sql("""
                     SELECT factor_code,
                            raw_value,
                            normalized_value,
                            weight,
                            penalty_value,
                            CASE WHEN is_penalty THEN NULL
                                 ELSE COALESCE(normalized_value, 0) * COALESCE(weight, 0)
                            END AS contribution,
                            is_penalty,
                            is_imputed,
                            data_available
                     FROM score_factor
                     WHERE score_id = :scoreId
                     ORDER BY is_penalty, factor_code
                     """)
                .param("scoreId", scoreId)
                .query((rs, rowNum) -> new FactorBreakdownRow(
                        rs.getString("factor_code"),
                        rs.getBigDecimal("raw_value"),
                        rs.getBigDecimal("normalized_value"),
                        rs.getBigDecimal("weight"),
                        rs.getBigDecimal("penalty_value"),
                        rs.getBigDecimal("contribution"),
                        rs.getBoolean("is_penalty"),
                        rs.getBoolean("is_imputed"),
                        rs.getBoolean("data_available")))
                .list();
    }

    private static ScoreRankingRow mapRanking(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        int confidence = rs.getInt("confidence");
        return new ScoreRankingRow(
                rs.getLong("score_id"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getString("scene_type"),
                rs.getBigDecimal("bonus_subtotal"),
                rs.getBigDecimal("penalty_subtotal"),
                rs.getBigDecimal("final_score"),
                rs.getString("grade"),
                confidence,
                // §5.9：信心度低於 50 於排行與詳情頁顯示警示標記
                confidence < 50);
    }
}
