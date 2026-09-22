package com.example.ssds.api.scoring.factor;

import com.example.ssds.api.scoring.factor.InventoryRiskFactorProvider.InventoryRule;
import com.example.ssds.api.scoring.factor.LogisticsRiskFactorProvider.LogisticsRule;
import com.example.ssds.infra.dao.RiskRuleDao;
import com.example.ssds.infra.dao.RiskRuleDao.RiskRuleData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/** 將 risk_rule JSON 轉成三個扣分 provider 的強型別設定。 */
@Service
public class ScoringRiskRuleService {
    private final RiskRuleDao riskRuleDao;
    private final ObjectMapper objectMapper;

    public ScoringRiskRuleService(RiskRuleDao riskRuleDao, ObjectMapper objectMapper) {
        this.riskRuleDao = riskRuleDao;
        this.objectMapper = objectMapper;
    }

    public ReviewRule reviewRule(Long categoryId) {
        JsonNode json = json(rule("REVIEW_RISK", categoryId));
        return new ReviewRule(decimal(json, "negativeRateThreshold"), integer(json, "minSampleSize"));
    }

    public LogisticsRule logisticsRule(Long categoryId) {
        RiskRuleData rule = rule("LOGISTICS_RISK", categoryId);
        JsonNode json = json(rule);
        return new LogisticsRule(
                decimal(json, "meltableSummerPoints"),
                decimal(json, "coldChainPoints"),
                decimal(json, "fragilePoints"),
                decimal(json, "oversizedPoints"),
                requiredMaxPenalty(rule, "LOGISTICS_RISK"));
    }

    public InventoryRule inventoryRule(Long categoryId) {
        RiskRuleData rule = rule("INVENTORY_RISK", categoryId);
        JsonNode json = json(rule);
        return new InventoryRule(
                integer(json, "shelfLifeDaysThreshold"),
                decimal(json, "shortShelfLifePoints"),
                decimal(json, "seasonalPoints"),
                integer(json, "moqThreshold"),
                decimal(json, "highMoqPoints"),
                requiredMaxPenalty(rule, "INVENTORY_RISK"));
    }

    private RiskRuleData rule(String code, Long categoryId) {
        return riskRuleDao.findEffective(code, categoryId)
                .orElseThrow(() -> new IllegalStateException("缺少有效風險規則：" + code));
    }

    private JsonNode json(RiskRuleData rule) {
        try {
            return objectMapper.readTree(rule.thresholdJson());
        } catch (Exception exception) {
            throw new IllegalStateException("風險規則 JSON 格式錯誤", exception);
        }
    }

    private static BigDecimal decimal(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("風險規則缺少數值欄位：" + field);
        }
        return value.decimalValue();
    }

    private static int integer(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalStateException("風險規則缺少整數欄位：" + field);
        }
        return value.intValue();
    }

    private static BigDecimal requiredMaxPenalty(RiskRuleData rule, String code) {
        if (rule.maxPenalty() == null) {
            throw new IllegalStateException(code + " 缺少 max_penalty");
        }
        return rule.maxPenalty();
    }

    public record ReviewRule(BigDecimal negativeRateThreshold, int minSampleSize) {}
}
