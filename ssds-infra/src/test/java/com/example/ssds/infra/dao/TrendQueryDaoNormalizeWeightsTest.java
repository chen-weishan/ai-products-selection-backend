package com.example.ssds.infra.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-14-4：來源不可用時，其餘來源權重自動重新正規化，記錄實際比例。
 * SQL 端只挑出「enabled 且非 UNAVAILABLE、且當日有讀值」的來源，並把品類級乘上 0.5 粒度折扣；
 * 這裡驗證 Java 端把剩下的有效權重除以總和的那一步。
 */
class TrendQueryDaoNormalizeWeightsTest {

    private static Map<String, Object> row(String code, String effectiveWeight) {
        return Map.of("sourceCode", code, "effectiveWeight", new BigDecimal(effectiveWeight));
    }

    private static BigDecimal sum(Map<String, BigDecimal> weights) {
        return weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("Instagram、人工不可用，只剩 Threads 0.300 與 Trends 0.250 → 0.5455／0.4545，總和 1")
    void twoSourcesRemainAfterDegradation() {
        Map<String, BigDecimal> result = TrendQueryDao.normalizeWeights(
                List.of(row("THREADS", "0.300"), row("GOOGLE_TRENDS", "0.250")));

        assertEquals(2, result.size());
        assertEquals(0, new BigDecimal("0.5455").compareTo(result.get("THREADS")));
        assertEquals(0, new BigDecimal("0.4545").compareTo(result.get("GOOGLE_TRENDS")));
        assertEquals(0, BigDecimal.ONE.compareTo(sum(result)));
    }

    @Test
    @DisplayName("品類級來源的 0.5 粒度折扣（SQL 已乘）併入分母：0.300／0.250／0.075 → 0.48／0.40／0.12")
    void categoryLevelDiscountIsReflectedInRatios() {
        Map<String, BigDecimal> result = TrendQueryDao.normalizeWeights(List.of(
                row("THREADS", "0.300"), row("GOOGLE_TRENDS", "0.250"), row("INSTAGRAM", "0.075")));

        assertEquals(0, new BigDecimal("0.4800").compareTo(result.get("THREADS")));
        assertEquals(0, new BigDecimal("0.4000").compareTo(result.get("GOOGLE_TRENDS")));
        assertEquals(0, new BigDecimal("0.1200").compareTo(result.get("INSTAGRAM")));
    }

    @Test
    @DisplayName("只剩一個來源時比例為 1")
    void singleSourceGetsFullWeight() {
        Map<String, BigDecimal> result = TrendQueryDao.normalizeWeights(List.of(row("THREADS", "0.300")));

        assertEquals(0, BigDecimal.ONE.compareTo(result.get("THREADS")));
    }

    @Test
    @DisplayName("沒有任何可用來源，或有效權重總和為 0：回傳空 Map，不除以 0")
    void emptyOrZeroTotalReturnsEmpty() {
        assertTrue(TrendQueryDao.normalizeWeights(List.of()).isEmpty());
        assertTrue(TrendQueryDao.normalizeWeights(
                        List.of(row("THREADS", "0.000"), row("GOOGLE_TRENDS", "0.000")))
                .isEmpty());
    }

    @Test
    @DisplayName("維持輸入順序，畫面「本次合成比例」的排列才穩定")
    void preservesInputOrder() {
        Map<String, BigDecimal> result = TrendQueryDao.normalizeWeights(
                List.of(row("GOOGLE_TRENDS", "0.250"), row("THREADS", "0.300")));

        assertEquals(List.of("GOOGLE_TRENDS", "THREADS"), List.copyOf(result.keySet()));
    }
}
