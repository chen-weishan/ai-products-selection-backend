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
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.dao.HeatAlertDao;
import com.example.ssds.infra.dao.projection.HeatAlertRow;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.ProductRepository;

/** 熱度示警偵測的串接：讀取 → 判定 → 寫入，以及每筆獨立失敗不影響整批。 */
class RiskHeatAlertServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static final Instant NOW = Instant.parse("2026-10-04T22:30:00Z");

    private final HeatAlertDao dao = mock(HeatAlertDao.class);
    private final RiskAlertRuleService rules = mock(RiskAlertRuleService.class);
    private final RiskAlertWriter writer = mock(RiskAlertWriter.class);
    private final ProductRepository products = mock(ProductRepository.class);

    private RiskHeatAlertService service;

    @BeforeEach
    void setUp() {
        when(rules.heatCrashSlopeThreshold(anyLong())).thenReturn(new BigDecimal("-0.40"));
        when(rules.heatSurgePercentile(anyLong())).thenReturn(new BigDecimal("0.95"));
        when(products.getReferenceById(anyLong())).thenAnswer(call ->
                Product.builder().id(call.getArgument(0)).name("品項" + call.getArgument(0)).build());
        when(writer.raise(any(), anyString(), any(), anyString(), any())).thenReturn(Outcome.CREATED);
        service = new RiskHeatAlertService(dao, rules, writer, products, mock(PlatformTransactionManager.class));
    }

    private static HeatAlertRow row(long product, String status, long keyword, String slope) {
        return new HeatAlertRow(product, 1L, null, status, keyword, "kw" + keyword, new BigDecimal(slope), false);
    }

    @Test
    @DisplayName("偵測日沒有熱度合成資料：不寫任何示警，也不退回前一天")
    void noDataForTheDay() {
        when(dao.findProductHeat(DAY)).thenReturn(List.of());

        RiskHeatAlertService.Result result = service.detect(DAY, NOW);

        assertThat(result.productsEvaluated()).isZero();
        verify(writer, never()).raise(any(), anyString(), any(), anyString(), any());
        verify(dao).findProductHeat(DAY);
    }

    @Test
    @DisplayName("HEAT_CRASH 以偵測時刻寫入 writer：HIGH、帶生效關鍵字")
    void crashIsWritten() {
        when(dao.findProductHeat(DAY)).thenReturn(List.of(row(1, "ADOPTED", 11, "-0.55")));

        RiskHeatAlertService.Result result = service.detect(DAY, NOW);

        assertThat(result.detected()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        verify(writer).raise(
                any(Product.class),
                eq("HEAT_CRASH"),
                eq(Severity.HIGH),
                eq("7 日熱度斜率 -55.0%（門檻 -40.0%）；關鍵字：kw11"),
                eq(NOW));
    }

    /** §5.3.3 慣例：多關鍵字取最高者。品項還有一個沒崩的關鍵字，就不算急墜。 */
    @Test
    @DisplayName("品項有多個關鍵字時取斜率最高者，另一個關鍵字沒崩就不開 HEAT_CRASH")
    void usesHighestSlopeKeywordPerProduct() {
        // DAO 依 slope_7d 由大到小回傳：品項 1 的最高是 −0.45（會觸發）；品項 2 的最高是 −0.10（不觸發）
        when(dao.findProductHeat(DAY)).thenReturn(List.of(
                row(1, "ADOPTED", 11, "-0.45"),
                row(1, "ADOPTED", 12, "-0.60"),
                row(2, "LISTED", 21, "-0.10"),
                row(2, "LISTED", 22, "-0.70")));

        RiskHeatAlertService.Result result = service.detect(DAY, NOW);

        assertThat(result.productsEvaluated()).isEqualTo(2);
        assertThat(result.detected()).isEqualTo(1);
        verify(writer).raise(any(Product.class), eq("HEAT_CRASH"), eq(Severity.HIGH),
                eq("7 日熱度斜率 -45.0%（門檻 -40.0%）；關鍵字：kw11"), eq(NOW));
    }

    @Test
    @DisplayName("門檻以品類為單位只查一次")
    void thresholdsAreCachedPerCategory() {
        when(dao.findProductHeat(DAY)).thenReturn(List.of(
                row(1, "ADOPTED", 11, "-0.55"),
                row(2, "ADOPTED", 21, "-0.50"),
                row(3, "LISTED", 31, "-0.45")));

        service.detect(DAY, NOW);

        verify(rules, times(1)).heatCrashSlopeThreshold(1L);
    }

    @Test
    @DisplayName("結果統計新增、更新、抑制各幾筆")
    void countsOutcomes() {
        when(dao.findProductHeat(DAY)).thenReturn(List.of(
                row(1, "ADOPTED", 11, "-0.55"),
                row(2, "ADOPTED", 21, "-0.55"),
                row(3, "ADOPTED", 31, "-0.55")));
        when(writer.raise(any(), anyString(), any(), anyString(), any()))
                .thenReturn(Outcome.CREATED, Outcome.REFRESHED, Outcome.SUPPRESSED);

        RiskHeatAlertService.Result result = service.detect(DAY, NOW);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.refreshed()).isEqualTo(1);
        assertThat(result.suppressed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("單筆寫入失敗只計入失敗數，後面的品項照常處理")
    void oneFailureDoesNotStopTheBatch() {
        when(dao.findProductHeat(DAY)).thenReturn(List.of(
                row(1, "ADOPTED", 11, "-0.55"),
                row(2, "ADOPTED", 21, "-0.55"),
                row(3, "ADOPTED", 31, "-0.55")));
        when(writer.raise(any(), anyString(), any(), anyString(), any()))
                .thenReturn(Outcome.CREATED)
                .thenThrow(new IllegalStateException("資料庫暫時失敗"))
                .thenReturn(Outcome.CREATED);

        RiskHeatAlertService.Result result = service.detect(DAY, NOW);

        assertThat(result.detected()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        verify(writer, times(3)).raise(any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("品項沒有任何異常時不寫入")
    void nothingDetected() {
        when(dao.findProductHeat(DAY)).thenReturn(List.of(row(1, "ADOPTED", 11, "0.05")));

        RiskHeatAlertService.Result result = service.detect(DAY, NOW);

        assertThat(result.productsEvaluated()).isEqualTo(1);
        assertThat(result.detected()).isZero();
        verify(writer, never()).raise(any(), anyString(), any(), anyString(), any());
        Mockito.verifyNoMoreInteractions(writer);
    }
}
