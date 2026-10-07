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
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.dao.FestivalAlertDao;
import com.example.ssds.infra.dao.projection.FestivalAlertRow;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.ProductRepository;

/** 檔期窗示警偵測的串接：讀取 → 判定 → 寫入，以及每筆獨立失敗不影響整批。 */
class RiskFestivalAlertServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final Instant NOW = Instant.parse("2026-06-30T22:35:00Z");
    private static final int LEAD = 21;

    private final FestivalAlertDao dao = mock(FestivalAlertDao.class);
    private final RiskAlertRuleService rules = mock(RiskAlertRuleService.class);
    private final RiskAlertWriter writer = mock(RiskAlertWriter.class);
    private final ProductRepository products = mock(ProductRepository.class);

    private RiskFestivalAlertService service;

    @BeforeEach
    void setUp() {
        when(rules.festivalWindowClosingDays(anyLong())).thenReturn(7);
        when(products.getReferenceById(anyLong())).thenAnswer(call ->
                Product.builder().id(call.getArgument(0)).name("品項" + call.getArgument(0)).build());
        when(writer.raise(any(), anyString(), any(), anyString(), any())).thenReturn(Outcome.CREATED);
        service = new RiskFestivalAlertService(dao, rules, writer, products, mock(PlatformTransactionManager.class));
    }

    private static FestivalAlertRow row(long product, Integer lead, long daysUntilFestival) {
        return new FestivalAlertRow(product, 1L, lead, "MID_AUTUMN", "中秋",
                TODAY.plusDays(daysUntilFestival), new BigDecimal("0.60"));
    }

    @Test
    @DisplayName("沒有候選列：不寫任何示警")
    void nothingToEvaluate() {
        when(dao.findUpcomingFestivalsWithoutDecision(TODAY)).thenReturn(List.of());

        RiskFestivalAlertService.Result result = service.detect(TODAY, NOW);

        assertThat(result.candidates()).isZero();
        verify(writer, never()).raise(any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("窗即將關閉：以偵測時刻寫入 writer，LOW")
    void closingWindowIsWritten() {
        when(dao.findUpcomingFestivalsWithoutDecision(TODAY)).thenReturn(List.of(row(1, LEAD, LEAD + 2)));

        RiskFestivalAlertService.Result result = service.detect(TODAY, NOW);

        assertThat(result.detected()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        verify(writer).raise(
                any(Product.class),
                eq("FESTIVAL_WINDOW_CLOSING"),
                eq(Severity.LOW),
                eq("中秋 檔期窗將於 3 日後由 1.0 降為 0.5（節慶日 2026-07-24，前置 21 日，關聯度 0.6）"),
                eq(NOW));
    }

    @Test
    @DisplayName("門檻以品類為單位只查一次")
    void thresholdQueriedOncePerCategory() {
        when(dao.findUpcomingFestivalsWithoutDecision(TODAY)).thenReturn(List.of(
                row(1, LEAD, LEAD + 30), row(2, LEAD, LEAD + 30), row(3, LEAD, LEAD + 30)));

        service.detect(TODAY, NOW);

        verify(rules, times(1)).festivalWindowClosingDays(1L);
    }

    @Test
    @DisplayName("writer 回報 REFRESHED／SUPPRESSED：分別計入，不算失敗")
    void outcomesAreCounted() {
        when(dao.findUpcomingFestivalsWithoutDecision(TODAY)).thenReturn(List.of(
                row(1, LEAD, LEAD), row(2, LEAD, LEAD), row(3, LEAD, LEAD)));
        when(writer.raise(any(), anyString(), any(), anyString(), any()))
                .thenReturn(Outcome.CREATED, Outcome.REFRESHED, Outcome.SUPPRESSED);

        RiskFestivalAlertService.Result result = service.detect(TODAY, NOW);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.suppressed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("單筆寫入失敗：計入 failed，其餘照常寫入")
    void oneFailureDoesNotStopTheBatch() {
        when(dao.findUpcomingFestivalsWithoutDecision(TODAY)).thenReturn(List.of(
                row(1, LEAD, LEAD), row(2, LEAD, LEAD)));
        when(writer.raise(any(), anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Outcome.CREATED);

        RiskFestivalAlertService.Result result = service.detect(TODAY, NOW);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    @DisplayName("品類沒有前置天數：不開立，計入 skippedNoLeadTime")
    void missingLeadTimeIsCounted() {
        when(dao.findUpcomingFestivalsWithoutDecision(TODAY)).thenReturn(List.of(row(1, null, 3)));

        RiskFestivalAlertService.Result result = service.detect(TODAY, NOW);

        assertThat(result.skippedNoLeadTime()).isEqualTo(1);
        assertThat(result.detected()).isZero();
        verify(writer, never()).raise(any(), anyString(), any(), anyString(), any());
    }
}
