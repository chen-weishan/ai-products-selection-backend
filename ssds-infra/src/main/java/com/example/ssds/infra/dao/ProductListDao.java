package com.example.ssds.infra.dao;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.projection.ProductListRow;
import com.example.ssds.infra.dao.query.ProductListCriteria;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * FR-03 品項清單唯讀查詢。
 *
 * <p>使用 JdbcClient 的原因：
 * 清單需要同時查 product、category、supplier、最新 product_score
 * 與 risk_alert。若用 Entity 關聯逐筆讀取，容易產生 N+1。
 */
@Repository
public class ProductListDao {

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("name", "p.name"),
            Map.entry("categoryName", "c.name"),
            Map.entry("supplierName", "supplier.name"),
            Map.entry("cost", "p.cost"),
            Map.entry("suggestedPrice", "p.suggested_price"),
            Map.entry("marginRate", "p.margin_rate"),
            Map.entry("latestScore", "latest_score.final_score"),
            Map.entry("grade", "latest_score.grade"),
            Map.entry("trackType", "p.track_type"),
            Map.entry("sourcingStatus", "p.sourcing_status"),
            Map.entry("status", "p.status"),
            Map.entry("updatedAt", "p.updated_at"));

    private static final String FROM_SQL = """
            FROM product p
            JOIN category c
              ON c.id = p.category_id
            LEFT JOIN supplier supplier
              ON supplier.id = p.supplier_id
            LEFT JOIN LATERAL (
                SELECT ps.final_score,
                       ps.grade,
                       ps.calculated_at
                FROM product_score ps
                WHERE ps.product_id = p.id
                ORDER BY ps.calculated_at DESC, ps.id DESC
                LIMIT 1
            ) latest_score ON TRUE
            """;

    private final JdbcClient jdbcClient;

    public ProductListDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Page<ProductListRow> search(ProductListCriteria criteria) {
        SqlFilter filter = buildFilter(criteria);

        String orderColumn = SORT_COLUMNS.get(criteria.sortField());

        if (orderColumn == null) {
            throw new IllegalArgumentException(
                    "不支援的排序欄位：" + criteria.sortField()
            );
        }

        String direction = criteria.ascending() ? "ASC" : "DESC";

        String selectSql = """
                SELECT p.id                  AS product_id,
                       p.name                AS product_name,
                       c.id                  AS category_id,
                       c.name                AS category_name,
                       supplier.id           AS supplier_id,
                       supplier.name         AS supplier_name,
                       p.cost,
                       p.suggested_price,
                       p.margin_rate,
                       latest_score.final_score AS latest_score,
                       latest_score.grade,
                       p.track_type,
                       p.sourcing_status,
                       p.status,
                       EXISTS (
                           SELECT 1
                           FROM risk_alert risk
                           WHERE risk.product_id = p.id
                             AND risk.status <> 'IGNORED'
                       ) AS has_risk,
                       p.updated_at
                """
                + FROM_SQL
                + filter.whereClause()
                + " ORDER BY "
                + orderColumn
                + " "
                + direction
                + " NULLS LAST, p.id ASC"
                + " LIMIT :limit OFFSET :offset";

        Map<String, Object> selectParameters =
                new HashMap<>(filter.parameters());

        selectParameters.put("limit", criteria.size());
        selectParameters.put(
                "offset",
                criteria.page() * criteria.size()
        );

        var content = jdbcClient
                .sql(selectSql)
                .params(selectParameters)
                .query(ProductListDao::mapRow)
                .list();

        String countSql =
                "SELECT COUNT(*) "
                + FROM_SQL
                + filter.whereClause();

        Long total = jdbcClient
                .sql(countSql)
                .params(filter.parameters())
                .query(Long.class)
                .single();

        return new PageImpl<>(
                content,
                PageRequest.of(criteria.page(), criteria.size()),
                total
        );
    }

    private SqlFilter buildFilter(ProductListCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        Map<String, Object> parameters = new HashMap<>();

        if (criteria.keyword() != null
                && !criteria.keyword().isBlank()) {
            where.append(" AND p.name ILIKE :keyword ");
            parameters.put(
                    "keyword",
                    "%" + criteria.keyword().trim() + "%"
            );
        }

        if (criteria.categoryId() != null) {
            where.append(" AND p.category_id = :categoryId ");
            parameters.put(
                    "categoryId",
                    criteria.categoryId()
            );
        }

        if (criteria.supplierId() != null) {
            where.append(" AND p.supplier_id = :supplierId ");
            parameters.put(
                    "supplierId",
                    criteria.supplierId()
            );
        }

        if (criteria.trackType() != null) {
            where.append(" AND p.track_type = :trackType ");
            parameters.put(
                    "trackType",
                    criteria.trackType().name()
            );
        }

        if (criteria.sourcingStatus() != null) {
            where.append(" AND p.sourcing_status = :sourcingStatus ");
            parameters.put(
                    "sourcingStatus",
                    criteria.sourcingStatus().name()
            );
        }

        if (criteria.status() != null) {
            where.append(" AND p.status = :status ");
            parameters.put(
                    "status",
                    criteria.status().name()
            );
        }

        if (criteria.grade() != null) {
            where.append(" AND latest_score.grade = :grade ");
            parameters.put(
                    "grade",
                    criteria.grade().name()
            );
        }

        if (criteria.minScore() != null) {
            where.append(
                    " AND latest_score.final_score >= :minScore "
            );
            parameters.put(
                    "minScore",
                    criteria.minScore()
            );
        }

        if (criteria.maxScore() != null) {
            where.append(
                    " AND latest_score.final_score <= :maxScore "
            );
            parameters.put(
                    "maxScore",
                    criteria.maxScore()
            );
        }

        if (criteria.hasRisk() != null) {
            String existsCondition = """
                    EXISTS (
                        SELECT 1
                        FROM risk_alert risk_filter
                        WHERE risk_filter.product_id = p.id
                          AND risk_filter.status <> 'IGNORED'
                    )
                    """;

            if (criteria.hasRisk()) {
                where.append(" AND ").append(existsCondition);
            } else {
                where.append(" AND NOT ").append(existsCondition);
            }
        }

        return new SqlFilter(
                where.toString(),
                Map.copyOf(parameters)
        );
    }

    private static ProductListRow mapRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {

        String gradeValue = resultSet.getString("grade");
        String sourcingStatusValue = resultSet.getString("sourcing_status");

        OffsetDateTime updatedAt = resultSet.getObject(
                "updated_at",
                OffsetDateTime.class
        );

        return new ProductListRow(
                resultSet.getLong("product_id"),
                resultSet.getString("product_name"),
                resultSet.getLong("category_id"),
                resultSet.getString("category_name"),
                resultSet.getObject(
                        "supplier_id",
                        Long.class
                ),
                resultSet.getString("supplier_name"),
                resultSet.getBigDecimal("cost"),
                resultSet.getBigDecimal("suggested_price"),
                resultSet.getBigDecimal("margin_rate"),
                resultSet.getBigDecimal("latest_score"),
                gradeValue == null
                        ? null
                        : Grade.valueOf(gradeValue),
                TrackType.valueOf(
                        resultSet.getString("track_type")
                ),
                sourcingStatusValue == null
                        ? null
                        : SourcingStatus.valueOf(sourcingStatusValue),
                ProductStatus.valueOf(
                        resultSet.getString("status")
                ),
                resultSet.getBoolean("has_risk"),
                updatedAt.toInstant()
        );
    }

    private record SqlFilter(
            String whereClause,
            Map<String, Object> parameters
    ) {
    }
}
