package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.dao.SeasonAlertDao;
import com.example.ssds.infra.dao.projection.SeasonAlertRow;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.ProductRepository;

/** 季節不匹配偵測的串接：讀取 → 判定 → 寫入，以及每筆獨立失敗不影響整批。 */
class RiskSeasonAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T01:00:00Z");

    private final SeasonAlertDao dao = mock(SeasonAlertDao.class);
    private final RiskAlertRuleService rules = mock(RiskAlertRuleService.class);
    private final RiskAlertWriter writer = mock(RiskAlertWriter.class);
    private final ProductRepository products = mock(ProductRepository.class);

    private RiskSeasonAlertService service;

    @BeforeEach
    void setUp() {
        when(rules.seasonMismatchPercentileThreshold(anyLong())).thenReturn(new BigDecimal("20"));
        when(products.getReferenceById(anyLong())).thenAnswer(call ->
                Product.builder().id(call.getArgument(0)).name("品項" + call.getArgument(0)).build());
        when(writer.raise(any(), anyString(), any(), anyString(), any())).thenReturn(Outcome.CREATED);
        service = new RiskSeasonAlertService(dao, rules, writer, products, mock(PlatformTransactionManager.class));
    }

    private static SeasonAlertRow row(long product, String status, String percentile, boolean available) {
        return new SeasonAlertRow(product, 1L, status, percentile == null ? null : new BigDecimal(percentile), available);
    }

    @Test
    @DisplayName("沒有任何具備氣候百分位的品項：不寫任何示警")
    void nothingToEvaluate() {
        when(dao.findLatestClimatePercentiles()).thenReturn(List.of());

        RiskSeasonAlertService.Result result = service.detect(NOW);

        assertThat(result.productsEvaluated()).isZero();
        verify(writer, never()).raise(any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("百分位低於門檻：以偵測時刻寫入 writer，MEDIUM")
    void mismatchIsWritten() {
        when(dao.findLatestClimatePercentiles()).thenReturn(List.of(row(1, "ADOPTED", "12", true)));

        RiskSeasonAlertService.Result result = service.detect(NOW);

        assertThat(result.detected()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        verify(writer).raise(
                any(Product.class),
                eq("SEASON_MISMATCH"),
                eq(Severity.MEDIUM),
                eq("季節氣候適配百分位 12（門檻 20）"),
                eq(NOW));
    }

    @Test
    @DisplayName("門檻以品類為單位只查一次，不逐品項查")
    void thresholdQueriedOncePerCategory() {
        when(dao.findLatestClimatePercentiles()).thenReturn(List.of(
                row(1, "ADOPTED", "50", true), row(2, "LISTED", "60", true), row(3, "ADOPTED", "70", true)));

        service.detect(NOW);

        verify(rules, times(1)).seasonMismatchPercentileThreshold(1L);
    }

    @Test
    @DisplayName("writer 回報 REFRESHED／SUPPRESSED：分別計入，不算失敗")
    void outcomesAreCounted() {
        when(dao.findLatestClimatePercentiles()).thenReturn(List.of(
                row(1, "ADOPTED", "5", true), row(2, "ADOPTED", "6", true), row(3, "ADOPTED", "7", true)));
        when(writer.raise(any(), anyString(), any(), anyString(), any()))
                .thenReturn(Outcome.CREATED, Outcome.REFRESHED, Outcome.SUPPRESSED);

        RiskSeasonAlertService.Result result = service.detect(NOW);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.suppressed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("單筆寫入失敗：計入 failed，其餘照常寫入")
    void oneFailureDoesNotStopTheBatch() {
        when(dao.findLatestClimatePercentiles()).thenReturn(List.of(
                row(1, "ADOPTED", "5", true), row(2, "ADOPTED", "6", true)));
        when(writer.raise(any(), anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Outcome.CREATED);

        RiskSeasonAlertService.Result result = service.detect(NOW);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    @DisplayName("因子無資料的品項不開立，並計入 skippedNoData")
    void noDataIsSkipped() {
        when(dao.findLatestClimatePercentiles()).thenReturn(List.of(row(1, "ADOPTED", null, false)));

        RiskSeasonAlertService.Result result = service.detect(NOW);

        assertThat(result.skippedNoData()).isEqualTo(1);
        assertThat(result.detected()).isZero();
        verify(writer, never()).raise(any(), anyString(), any(), anyString(), any());
    }
}
