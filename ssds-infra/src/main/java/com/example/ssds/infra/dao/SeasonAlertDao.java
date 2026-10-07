package com.example.ssds.infra.dao;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.ssds.infra.dao.projection.SeasonAlertRow;

/** 批次讀取 ADOPTED／LISTED 品項最近一份現行評分中的 CLIMATE 百分位。 */
@Repository
public class SeasonAlertDao {

    private final JdbcClient jdbcClient;

    public SeasonAlertDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SeasonAlertRow> findLatestClimatePercentiles() {
        return jdbcClient.sql("""
                        with latest_scores as (
                            select distinct on (s.product_id)
                                   s.id as score_id,
                                   s.product_id,
                                   s.calculated_at
                            from product_score s
                            join product p on p.id = s.product_id
                            where s.is_active = true
                              and s.is_primary = true
                              and p.deleted_at is null
                              and p.status in ('ADOPTED', 'LISTED')
                            order by s.product_id, s.calculated_at desc, s.id desc
                        )
                        select p.id as product_id,
                               p.category_id as category_id,
                               p.status as status,
                               sf.normalized_value as percentile,
                               coalesce(sf.data_available, false) as data_available
                        from latest_scores ls
                        join product p on p.id = ls.product_id
                        left join score_factor sf
                               on sf.score_id = ls.score_id and sf.factor_code = 'CLIMATE'
                        order by p.id
                        """)
                .query((rs, rowNum) -> new SeasonAlertRow(
                        rs.getLong("product_id"),
                        rs.getLong("category_id"),
                        rs.getString("status"),
                        rs.getBigDecimal("percentile"),
                        rs.getBoolean("data_available")))
                .list();
    }
}