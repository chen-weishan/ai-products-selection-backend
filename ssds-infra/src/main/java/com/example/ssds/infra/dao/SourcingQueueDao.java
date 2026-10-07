package com.example.ssds.infra.dao;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.dao.projection.SourcingQueueCounts;
import com.example.ssds.infra.dao.projection.SourcingQueueRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 尋源優先序畫面的精簡唯讀查詢，避免逐品項載入完整探索報告。 */
@Repository
public class SourcingQueueDao {

    private static final String BASE_FILTER = """
            p.track_type = 'B'
              AND p.deleted_at IS NULL
            """;

    // Sort the complete result before pagination; rejected items use the opposite gap order.
    static final String ORDER_BY_SQL = """
             ORDER BY CASE sourcing_status
                          WHEN 'URGENT' THEN 0
                          WHEN 'SOURCING' THEN 1
                          WHEN 'PENDING' THEN 2
                          WHEN 'PROMOTED' THEN 3
                          WHEN 'REJECTED' THEN 4
                          ELSE 5
                      END,
                      CASE WHEN sourcing_status = 'REJECTED' THEN time_gap_days END DESC NULLS LAST,
                      CASE WHEN sourcing_status <> 'REJECTED' THEN time_gap_days END ASC NULLS LAST,
                      product_id ASC
            """;

    private final JdbcClient jdbcClient;

    public SourcingQueueDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SourcingQueueRow> findPage(
            Set<SourcingStatus> statuses, int page, int size) {
        Filter filter = filter(statuses);
        String sql = """
                SELECT * FROM (
                SELECT p.id AS product_id,
                       p.name AS keyword,
                       latest.stage AS heat_stage,
                       latest.stage_weeks,
                       latest.estimated_lifespan_days,
                       candidate.lead_time_days,
                       CASE
                           WHEN latest.estimated_lifespan_days IS NULL THEN NULL
                           ELSE latest.estimated_lifespan_days - candidate.lead_time_days
                       END AS time_gap_days,
                       p.sourcing_status
                FROM product p
                JOIN sourcing_candidate candidate
                  ON candidate.product_id = p.id
                LEFT JOIN trend_keyword driving
                  ON driving.id = candidate.driving_keyword_id
                 AND driving.enabled = TRUE
                LEFT JOIN LATERAL (
                    SELECT composite.stage,
                           composite.stage_weeks,
                           composite.estimated_lifespan_days
                    FROM heat_composite_daily composite
                    WHERE composite.keyword_id = driving.id
                    ORDER BY composite.stat_date DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE
                """
                + BASE_FILTER
                + filter.sql()
                + ") queue\n"
                + ORDER_BY_SQL
                + """
                 LIMIT :limit OFFSET :offset
                """;

        Map<String, Object> parameters = new HashMap<>(filter.parameters());
        parameters.put("limit", size);
        parameters.put("offset", page * size);
        return jdbcClient.sql(sql)
                .params(parameters)
                .query(SourcingQueueDao::mapRow)
                .list();
    }

    public SourcingQueueCounts count(Set<SourcingStatus> statuses) {
        Filter filter = filter(statuses);
        String sql = """
                SELECT COUNT(*) FILTER (WHERE TRUE
                """
                + filter.sql()
                + """
                       ) AS total_elements,
                       COUNT(*) FILTER (WHERE p.sourcing_status IN ('PENDING', 'SOURCING', 'URGENT'))
                           AS active_count,
                       COUNT(*) FILTER (WHERE p.sourcing_status = 'REJECTED') AS rejected_count,
                       COUNT(*) FILTER (WHERE p.sourcing_status = 'PROMOTED') AS promoted_count
                FROM product p
                JOIN sourcing_candidate candidate
                  ON candidate.product_id = p.id
                WHERE
                """
                + BASE_FILTER;

        return jdbcClient.sql(sql)
                .params(filter.parameters())
                .query((resultSet, rowNumber) -> new SourcingQueueCounts(
                        resultSet.getLong("total_elements"),
                        resultSet.getLong("active_count"),
                        resultSet.getLong("rejected_count"),
                        resultSet.getLong("promoted_count")))
                .single();
    }

    private static Filter filter(Set<SourcingStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return new Filter("", Map.of());
        }
        return new Filter(
                " AND p.sourcing_status IN (:statuses)",
                Map.of("statuses", statuses.stream().map(Enum::name).toList()));
    }

    private static SourcingQueueRow mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String heatStage = resultSet.getString("heat_stage");
        String sourcingStatus = resultSet.getString("sourcing_status");
        return new SourcingQueueRow(
                resultSet.getLong("product_id"),
                resultSet.getString("keyword"),
                heatStage == null ? null : HeatStage.valueOf(heatStage),
                resultSet.getObject("stage_weeks", Short.class),
                resultSet.getObject("estimated_lifespan_days", Integer.class),
                resultSet.getObject("lead_time_days", Integer.class),
                resultSet.getObject("time_gap_days", Integer.class),
                SourcingStatus.valueOf(sourcingStatus));
    }

    private record Filter(String sql, Map<String, Object> parameters) {
    }
}
