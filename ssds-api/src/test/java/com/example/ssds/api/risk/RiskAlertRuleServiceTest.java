package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ssds.infra.dao.RiskRuleDao;
import com.example.ssds.infra.dao.RiskRuleDao.RiskRuleData;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 示警門檻讀取：有規則用規則，缺規則或格式壞掉時退回規格書預設，不讓評分跟著失敗。 */
@ExtendWith(MockitoExtension.class)
class RiskAlertRuleServiceTest {

    @Mock
    private RiskRuleDao riskRuleDao;

    private RiskAlertRuleService service() {
        return new RiskAlertRuleService(riskRuleDao, new ObjectMapper());
    }

    @Test
    @DisplayName("讀取 risk_rule 的 confidenceThreshold")
    void readsConfiguredThreshold() {
        when(riskRuleDao.findEffective("LOW_CONFIDENCE", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"confidenceThreshold\": 65}", null)));

        assertThat(service().lowConfidenceThreshold(3L)).isEqualTo(65);
    }

    @Test
    @DisplayName("缺規則：退回規格書預設 50")
    void fallsBackWhenRuleMissing() {
        when(riskRuleDao.findEffective("LOW_CONFIDENCE", 3L)).thenReturn(Optional.empty());

        assertThat(service().lowConfidenceThreshold(3L)).isEqualTo(50);
    }

    @Test
    @DisplayName("欄位缺少、型別不對或 JSON 壞掉：退回預設 50")
    void fallsBackWhenMalformed() {
        when(riskRuleDao.findEffective("LOW_CONFIDENCE", 1L))
                .thenReturn(Optional.of(new RiskRuleData("{\"other\": 1}", null)));
        when(riskRuleDao.findEffective("LOW_CONFIDENCE", 2L))
                .thenReturn(Optional.of(new RiskRuleData("{\"confidenceThreshold\": \"50\"}", null)));
        when(riskRuleDao.findEffective("LOW_CONFIDENCE", 3L))
                .thenReturn(Optional.of(new RiskRuleData("not-json", null)));

        assertThat(service().lowConfidenceThreshold(1L)).isEqualTo(50);
        assertThat(service().lowConfidenceThreshold(2L)).isEqualTo(50);
        assertThat(service().lowConfidenceThreshold(3L)).isEqualTo(50);
    }

    // ---- HEAT_CRASH／HEAT_SURGE 門檻 ----

    @Test
    @DisplayName("HEAT_CRASH：讀取 slope7dThreshold，品類覆寫優先")
    void readsHeatCrashThreshold() {
        when(riskRuleDao.findEffective("HEAT_CRASH", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slope7dThreshold\": -0.25}", null)));

        assertThat(service().heatCrashSlopeThreshold(3L)).isEqualByComparingTo("-0.25");
    }

    @Test
    @DisplayName("HEAT_CRASH：缺規則、非負數或型別不對都退回 −0.40")
    void heatCrashFallsBack() {
        when(riskRuleDao.findEffective("HEAT_CRASH", 1L)).thenReturn(Optional.empty());
        // 0 或正數會讓幾乎所有品項都「急墜」，視為格式錯誤
        when(riskRuleDao.findEffective("HEAT_CRASH", 2L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slope7dThreshold\": 0.40}", null)));
        when(riskRuleDao.findEffective("HEAT_CRASH", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slope7dThreshold\": \"-0.4\"}", null)));

        assertThat(service().heatCrashSlopeThreshold(1L)).isEqualByComparingTo(new BigDecimal("-0.40"));
        assertThat(service().heatCrashSlopeThreshold(2L)).isEqualByComparingTo(new BigDecimal("-0.40"));
        assertThat(service().heatCrashSlopeThreshold(3L)).isEqualByComparingTo(new BigDecimal("-0.40"));
    }

    @Test
    @DisplayName("HEAT_SURGE：讀取 slopePercentile（V34 之後的結構）")
    void readsHeatSurgePercentile() {
        when(riskRuleDao.findEffective("HEAT_SURGE", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slopePercentile\": 0.90}", null)));

        assertThat(service().heatSurgePercentile(3L)).isEqualByComparingTo("0.90");
    }

    @Test
    @DisplayName("HEAT_SURGE：舊結構、0、超過 1 或缺規則都退回 0.95，不會悄悄改用固定斜率 0.60")
    void heatSurgeFallsBack() {
        when(riskRuleDao.findEffective("HEAT_SURGE", 1L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slope7dThreshold\": 0.60}", null)));
        when(riskRuleDao.findEffective("HEAT_SURGE", 2L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slopePercentile\": 0}", null)));
        when(riskRuleDao.findEffective("HEAT_SURGE", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slopePercentile\": 1.5}", null)));
        when(riskRuleDao.findEffective("HEAT_SURGE", 4L)).thenReturn(Optional.empty());

        for (long category = 1; category <= 4; category++) {
            assertThat(service().heatSurgePercentile(category)).isEqualByComparingTo("0.95");
        }
    }

    @Test
    @DisplayName("HEAT_SURGE：百分位恰為 1（P100）是合法值")
    void heatSurgePercentileUpperBoundIsValid() {
        when(riskRuleDao.findEffective("HEAT_SURGE", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"slopePercentile\": 1}", null)));

        assertThat(service().heatSurgePercentile(3L)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("SEASON_MISMATCH：讀取 climateFitPercentileThreshold")
    void readsSeasonThreshold() {
        when(riskRuleDao.findEffective("SEASON_MISMATCH", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"climateFitPercentileThreshold\": 30}", null)));

        assertThat(service().seasonMismatchPercentileThreshold(3L)).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("SEASON_MISMATCH：缺規則、舊欄位、0、超過 100 或型別不對，一律退回預設 20")
    void seasonThresholdFallsBack() {
        when(riskRuleDao.findEffective("SEASON_MISMATCH", 1L)).thenReturn(Optional.empty());
        when(riskRuleDao.findEffective("SEASON_MISMATCH", 2L))
                .thenReturn(Optional.of(new RiskRuleData("{\"tempDeviationThreshold\": 8.0}", null)));
        when(riskRuleDao.findEffective("SEASON_MISMATCH", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"climateFitPercentileThreshold\": 0}", null)));
        when(riskRuleDao.findEffective("SEASON_MISMATCH", 4L))
                .thenReturn(Optional.of(new RiskRuleData("{\"climateFitPercentileThreshold\": 101}", null)));
        when(riskRuleDao.findEffective("SEASON_MISMATCH", 5L))
                .thenReturn(Optional.of(new RiskRuleData("{\"climateFitPercentileThreshold\": \"20\"}", null)));

        for (long categoryId = 1; categoryId <= 5; categoryId++) {
            assertThat(service().seasonMismatchPercentileThreshold(categoryId)).isEqualByComparingTo("20");
        }
    }

    @Test
    @DisplayName("FESTIVAL_WINDOW_CLOSING：讀取 daysBeforeLeadTimeCutoff")
    void readsFestivalClosingDays() {
        when(riskRuleDao.findEffective("FESTIVAL_WINDOW_CLOSING", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"daysBeforeLeadTimeCutoff\": 10}", null)));

        assertThat(service().festivalWindowClosingDays(3L)).isEqualTo(10);
    }

    @Test
    @DisplayName("FESTIVAL_WINDOW_CLOSING：缺規則、0、超過 30、小數或型別不對，一律退回預設 7")
    void festivalClosingDaysFallsBack() {
        when(riskRuleDao.findEffective("FESTIVAL_WINDOW_CLOSING", 1L)).thenReturn(Optional.empty());
        when(riskRuleDao.findEffective("FESTIVAL_WINDOW_CLOSING", 2L))
                .thenReturn(Optional.of(new RiskRuleData("{\"daysBeforeLeadTimeCutoff\": 0}", null)));
        when(riskRuleDao.findEffective("FESTIVAL_WINDOW_CLOSING", 3L))
                .thenReturn(Optional.of(new RiskRuleData("{\"daysBeforeLeadTimeCutoff\": 31}", null)));
        when(riskRuleDao.findEffective("FESTIVAL_WINDOW_CLOSING", 4L))
                .thenReturn(Optional.of(new RiskRuleData("{\"daysBeforeLeadTimeCutoff\": 7.5}", null)));
        when(riskRuleDao.findEffective("FESTIVAL_WINDOW_CLOSING", 5L))
                .thenReturn(Optional.of(new RiskRuleData("{\"daysBeforeLeadTimeCutoff\": \"7\"}", null)));

        for (long categoryId = 1; categoryId <= 5; categoryId++) {
            assertThat(service().festivalWindowClosingDays(categoryId)).isEqualTo(7);
        }
    }
}
