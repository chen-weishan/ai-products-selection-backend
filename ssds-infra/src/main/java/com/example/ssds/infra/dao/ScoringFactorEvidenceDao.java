package com.example.ssds.infra.dao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 尚未建立 JPA entity 的評分權威表之唯讀 evidence 查詢。 */
@Repository
public class ScoringFactorEvidenceDao {
    private final JdbcClient jdbcClient;

    public ScoringFactorEvidenceDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AudienceBandRow> findAudienceBands(Long categoryId) {
        return jdbcClient.sql("""
                        select a.audience_code, a.price_min, a.price_max, m.share
                        from category_audience_mix m
                        join audience_segment a on a.id = m.audience_id
                        where m.category_id = :categoryId
                        order by a.id
                        """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new AudienceBandRow(
                        rs.getString("audience_code"),
                        rs.getBigDecimal("price_min"),
                        rs.getBigDecimal("price_max"),
                        rs.getBigDecimal("share")))
                .list();
    }

    public Optional<ClimateProfileRow> findClimateProfile(Long categoryId) {
        return jdbcClient.sql("""
                        select ideal_temp_min, ideal_temp_max, tolerance
                        from category_climate_profile
                        where category_id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new ClimateProfileRow(
                        rs.getBigDecimal("ideal_temp_min"),
                        rs.getBigDecimal("ideal_temp_max"),
                        rs.getBigDecimal("tolerance")))
                .optional();
    }

    public ReviewStats findReviewStats(Long productId) {
        return jdbcClient.sql("""
                        select count(a.review_id) as total_count,
                               count(*) filter (where a.sentiment = 'NEGATIVE') as negative_count,
                               count(*) filter (
                                   where a.sentiment = 'NEGATIVE'
                                     and a.risk_topic in ('QUALITY', 'FOOD_SAFETY', 'SHIPPING_DAMAGE')
                               ) as risk_topic_negative_count
                        from review_analysis a
                        join product_review r on r.id = a.review_id
                        where r.product_id = :productId
                        """)
                .param("productId", productId)
                .query((rs, rowNum) -> new ReviewStats(
                        rs.getInt("total_count"),
                        rs.getInt("negative_count"),
                        rs.getInt("risk_topic_negative_count")))
                .single();
    }

    public record AudienceBandRow(
            String audienceCode, BigDecimal priceMin, BigDecimal priceMax, BigDecimal share) {}

    public record ClimateProfileRow(BigDecimal idealTempMin, BigDecimal idealTempMax, BigDecimal tolerance) {}

    public record ReviewStats(int totalCount, int negativeCount, int riskTopicNegativeCount) {}
}
