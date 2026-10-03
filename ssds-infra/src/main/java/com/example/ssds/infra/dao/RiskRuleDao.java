package com.example.ssds.infra.dao;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 取得品類覆寫優先、全域預設次之的有效 risk_rule。 */
@Repository
public class RiskRuleDao {
    private final JdbcClient jdbcClient;

    public RiskRuleDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<RiskRuleData> findEffective(String ruleCode, Long categoryId) {
        return jdbcClient.sql("""
                        select threshold_json::text as threshold_json, max_penalty
                        from risk_rule
                        where rule_code = :ruleCode
                          and enabled = true
                          and (category_id = :categoryId or category_id is null)
                        order by (category_id is null), id desc
                        limit 1
                        """)
                .param("ruleCode", ruleCode)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new RiskRuleData(
                        rs.getString("threshold_json"), rs.getBigDecimal("max_penalty")))
                .optional();
    }

    public record RiskRuleData(String thresholdJson, BigDecimal maxPenalty) {}
}
