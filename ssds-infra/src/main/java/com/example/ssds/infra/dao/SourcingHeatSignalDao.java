package com.example.ssds.infra.dao;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.infra.dao.projection.SourcingHeatSignal;
import java.util.Collection;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 從每日合成熱度中取得品項關鍵字最新且最強的階段訊號。 */
@Repository
public class SourcingHeatSignalDao {

    private final JdbcClient jdbcClient;

    public SourcingHeatSignalDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<SourcingHeatSignal> findLatest(Collection<Long> keywordIds) {
        if (keywordIds == null || keywordIds.isEmpty()) {
            return Optional.empty();
        }

        return jdbcClient.sql("""
                SELECT keyword_id,
                       stage,
                       stage_weeks,
                       COALESCE(
                           estimated_lifespan_days,
                           CASE stage
                               WHEN 'RISING' THEN 56
                               WHEN 'PLATEAU' THEN
                                   CASE WHEN stage_weeks >= 3 THEN 35 ELSE 42 END
                               WHEN 'DECLINING' THEN 17
                               ELSE NULL
                           END
                       ) AS estimated_lifespan_days
                FROM heat_composite_daily
                WHERE keyword_id IN (:keywordIds)
                  AND stage IS NOT NULL
                ORDER BY stat_date DESC, composite_value DESC, keyword_id ASC
                LIMIT 1
                """)
                .param("keywordIds", keywordIds)
                .query((resultSet, rowNumber) -> new SourcingHeatSignal(
                        resultSet.getLong("keyword_id"),
                        HeatStage.valueOf(resultSet.getString("stage")),
                        resultSet.getShort("stage_weeks"),
                        resultSet.getObject("estimated_lifespan_days", Integer.class)
                ))
                .optional();
    }
}
