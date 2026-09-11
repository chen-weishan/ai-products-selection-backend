package com.example.ssds.infra.dao;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** FR-03 毛利率的類別中位數與評分百分位查詢。 */
@Repository
public class ProductMarginStatisticsDao {

    private final JdbcClient jdbcClient;

    public ProductMarginStatisticsDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CategoryMarginStatistics> findCategoryStatistics(Long categoryId) {
        return jdbcClient.sql("""
                        SELECT c.id AS category_id,
                               c.name AS category_name,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY p.margin_rate) AS median_margin_rate,
                               count(p.id) AS sample_count
                        FROM category c
                        LEFT JOIN product p
                          ON p.category_id = c.id
                         AND p.track_type = 'A'
                         AND p.deleted_at IS NULL
                         AND p.margin_rate IS NOT NULL
                        WHERE c.id = :categoryId
                        GROUP BY c.id, c.name
                        """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new CategoryMarginStatistics(
                        rs.getLong("category_id"),
                        rs.getString("category_name"),
                        rs.getBigDecimal("median_margin_rate"),
                        rs.getLong("sample_count")
                ))
                .optional();
    }

    public Optional<MarginPercentile> findPercentile(Long productId, Long categoryId) {
        Optional<MarginPercentile> category = percentile(productId, categoryId);
        if (category.isPresent() && category.get().sampleCount() >= 10) {
            return category;
        }
        return percentile(productId, null)
                .map(value -> new MarginPercentile(value.normalizedValue(), value.sampleCount(), true));
    }

    public Optional<GradeThreshold> findGradeThreshold(Long versionId, String sceneType) {
        return jdbcClient.sql("""
                        SELECT grade_a_min, grade_b_min
                        FROM grade_threshold
                        WHERE version_id = :versionId AND scene_type = :sceneType
                        """)
                .param("versionId", versionId)
                .param("sceneType", sceneType)
                .query((rs, rowNum) -> new GradeThreshold(
                        rs.getBigDecimal("grade_a_min"),
                        rs.getBigDecimal("grade_b_min")
                ))
                .optional();
    }

    private Optional<MarginPercentile> percentile(Long productId, Long categoryId) {
        String categoryFilter = categoryId == null ? "" : " AND category_id = :categoryId ";
        var query = jdbcClient.sql("""
                WITH ranked AS (
                    SELECT id,
                           cume_dist() OVER (ORDER BY margin_rate) * 100 AS percentile,
                           count(*) OVER () AS sample_count
                    FROM product
                    WHERE track_type = 'A'
                      AND deleted_at IS NULL
                      AND margin_rate IS NOT NULL
                """ + categoryFilter + """
                )
                SELECT percentile, sample_count FROM ranked WHERE id = :productId
                """).param("productId", productId);
        if (categoryId != null) query = query.param("categoryId", categoryId);
        return query.query((rs, rowNum) -> new MarginPercentile(
                rs.getBigDecimal("percentile"), rs.getLong("sample_count"), false
        )).optional();
    }

    public record CategoryMarginStatistics(
            Long categoryId,
            String categoryName,
            BigDecimal medianMarginRate,
            long sampleCount
    ) {}

    public record MarginPercentile(BigDecimal normalizedValue, long sampleCount, boolean imputed) {}

    public record GradeThreshold(BigDecimal gradeAMin, BigDecimal gradeBMin) {}
}
