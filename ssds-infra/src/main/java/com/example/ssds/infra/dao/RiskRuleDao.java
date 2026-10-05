package com.example.ssds.infra.dao;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
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
  public List<RiskRuleRecord> findAllRules() {
        return jdbcClient.sql("""
                        select id, rule_code, category_id, threshold_json::text as threshold_json,
                               max_penalty, enabled, updated_by, updated_at
                        from risk_rule
                        order by rule_code, category_id nulls first
                        """)
                .query((rs, rowNum) -> new RiskRuleRecord(
                        rs.getLong("id"), rs.getString("rule_code"), rs.getObject("category_id", Long.class),
                        rs.getString("threshold_json"), rs.getBigDecimal("max_penalty"),
                        rs.getBoolean("enabled"), rs.getObject("updated_by", Long.class),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class)))
                .list();
    }

    public Optional<RiskRuleRecord> findByCodeAndCategory(String ruleCode, Long categoryId) {
        return jdbcClient.sql("""
                        select id, rule_code, category_id, threshold_json::text as threshold_json,
                               max_penalty, enabled, updated_by, updated_at
                        from risk_rule
                        where rule_code = :ruleCode and category_id is not distinct from :categoryId
                        """)
                .param("ruleCode", ruleCode)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new RiskRuleRecord(
                        rs.getLong("id"), rs.getString("rule_code"), rs.getObject("category_id", Long.class),
                        rs.getString("threshold_json"), rs.getBigDecimal("max_penalty"),
                        rs.getBoolean("enabled"), rs.getObject("updated_by", Long.class),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class)))
                .optional();
    }

    public void upsert(
            String ruleCode, Long categoryId, String thresholdJson, BigDecimal maxPenalty, Long userId) {
        jdbcClient.sql("""
                        insert into risk_rule (rule_code, category_id, threshold_json, max_penalty, enabled, updated_by, updated_at)
                        values (:ruleCode, :categoryId, cast(:thresholdJson as jsonb), :maxPenalty, true, :userId, now())
                        on conflict on constraint uk_risk_rule do update
                        set threshold_json = excluded.threshold_json,
                            max_penalty = excluded.max_penalty,
                            enabled = true,
                            updated_by = excluded.updated_by,
                            updated_at = now()
                        """)
                .param("ruleCode", ruleCode)
                .param("categoryId", categoryId)
                .param("thresholdJson", thresholdJson)
                .param("maxPenalty", maxPenalty)
                .param("userId", userId)
                .update();
    }

    public record RiskRuleData(String thresholdJson, BigDecimal maxPenalty) {}

    public record RiskRuleRecord(
            Long id,
            String ruleCode,
            Long categoryId,
            String thresholdJson,
            BigDecimal maxPenalty,
            boolean enabled,
            Long updatedBy,
            java.time.OffsetDateTime updatedAt) {}
}

