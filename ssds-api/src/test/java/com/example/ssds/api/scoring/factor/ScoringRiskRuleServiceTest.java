package com.example.ssds.api.scoring.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.infra.dao.RiskRuleDao;
import com.example.ssds.infra.dao.RiskRuleDao.RiskRuleData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScoringRiskRuleServiceTest {
    private final RiskRuleDao rules = mock(RiskRuleDao.class);
    private final ScoringRiskRuleService service = new ScoringRiskRuleService(rules, new ObjectMapper());

    @Test
    void parsesAllProviderRulesFromRuntimeJson() {
        when(rules.findEffective("REVIEW_RISK", 10L)).thenReturn(Optional.of(new RiskRuleData(
                "{\"negativeRateThreshold\":0.18,\"minSampleSize\":25}", new BigDecimal("20"))));
        when(rules.findEffective("LOGISTICS_RISK", 10L)).thenReturn(Optional.of(new RiskRuleData(
                "{\"meltableSummerPoints\":4,\"coldChainPoints\":5,\"fragilePoints\":2,\"oversizedPoints\":3}",
                new BigDecimal("10"))));
        when(rules.findEffective("INVENTORY_RISK", 10L)).thenReturn(Optional.of(new RiskRuleData(
                "{\"shelfLifeDaysThreshold\":45,\"shortShelfLifePoints\":4,\"seasonalPoints\":3,"
                        + "\"moqThreshold\":250,\"highMoqPoints\":3}",
                new BigDecimal("10"))));

        assertEquals(new BigDecimal("0.18"), service.reviewRule(10L).negativeRateThreshold());
        assertEquals(25, service.reviewRule(10L).minSampleSize());
        assertEquals(new BigDecimal("5"), service.logisticsRule(10L).coldChainPoints());
        assertEquals(45, service.inventoryRule(10L).shelfLifeDaysThreshold());
        assertEquals(250, service.inventoryRule(10L).moqThreshold());
    }

    @Test
    void rejectsMissingConfiguredPointsInsteadOfSilentlyHardcoding() {
        when(rules.findEffective("LOGISTICS_RISK", 10L)).thenReturn(Optional.of(new RiskRuleData(
                "{\"coldChainPoints\":4}", new BigDecimal("10"))));

        assertThrows(IllegalStateException.class, () -> service.logisticsRule(10L));
    }
}
