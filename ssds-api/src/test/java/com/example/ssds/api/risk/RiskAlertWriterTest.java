package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.RiskAlertRepository;

/** FR-10-2／AC-10-6 的去重規則（含「忽略後 7 日內不重開」的決策 B）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskAlertWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-05T06:30:00Z");

    @Mock
    private RiskAlertRepository repository;

    @Mock
    private RiskAlertLocks locks;

    @InjectMocks
    private RiskAlertWriter writer;

    private Product product() {
        return Product.builder()
                .id(1L)
                .name("海鹽奶蓋餅乾")
                .category(Category.builder().id(1L).name("零食").build())
                .build();
    }

    private RiskAlert existing(AlertStatus status) {
        return RiskAlert.builder()
                .id(77L)
                .product(product())
                .riskType(RiskTypes.PENALTY_CAP)
                .severity(Severity.MEDIUM)
                .status(status)
                .triggerValue("舊的觸發值")
                .detectedAt(Instant.parse("2026-10-01T06:30:00Z"))
                .build();
    }

    private void windowHas(RiskAlert alert) {
        when(repository.findWithinDedupWindow(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(alert));
    }

    @Test
    @DisplayName("窗內沒有同類示警時新開一筆 OPEN")
    void createsWhenNothingInWindow() {
        when(repository.findWithinDedupWindow(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());

        Outcome outcome = writer.raise(
                product(), RiskTypes.PENALTY_CAP, Severity.HIGH, "扣分小計 24.0", NOW);

        assertThat(outcome).isEqualTo(Outcome.CREATED);
        ArgumentCaptor<RiskAlert> saved = ArgumentCaptor.forClass(RiskAlert.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(saved.getValue().getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(saved.getValue().getRiskType()).isEqualTo(RiskTypes.PENALTY_CAP);
        assertThat(saved.getValue().getTriggerValue()).isEqualTo("扣分小計 24.0");
        assertThat(saved.getValue().getDetectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("窗內已有 OPEN：更新嚴重度、觸發值與偵測時間，不新開")
    void refreshesOpenAlert() {
        RiskAlert open = existing(AlertStatus.OPEN);
        windowHas(open);

        Outcome outcome = writer.raise(
                product(), RiskTypes.PENALTY_CAP, Severity.HIGH, "扣分小計 26.0", NOW);

        assertThat(outcome).isEqualTo(Outcome.REFRESHED);
        verify(repository).save(open);
        assertThat(open.getId()).isEqualTo(77L);
        assertThat(open.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(open.getTriggerValue()).isEqualTo("扣分小計 26.0");
        assertThat(open.getDetectedAt()).isEqualTo(NOW);
        assertThat(open.getStatus()).isEqualTo(AlertStatus.OPEN);
    }

    /** 決策 B：忽略只管當下，條件仍成立隔天又開一筆，「忽略」就形同虛設。 */
    @Test
    @DisplayName("窗內已有 IGNORED：不重開、不改動")
    void suppressedByIgnoredAlert() {
        RiskAlert ignored = existing(AlertStatus.IGNORED);
        windowHas(ignored);

        Outcome outcome = writer.raise(
                product(), RiskTypes.PENALTY_CAP, Severity.HIGH, "扣分小計 24.0", NOW);

        assertThat(outcome).isEqualTo(Outcome.SUPPRESSED);
        verify(repository, never()).save(any());
        assertThat(ignored.getTriggerValue()).isEqualTo("舊的觸發值");
        assertThat(ignored.getStatus()).isEqualTo(AlertStatus.IGNORED);
    }

    @Test
    @DisplayName("窗內已有 ACKNOWLEDGED：同樣不重開")
    void suppressedByAcknowledgedAlert() {
        windowHas(existing(AlertStatus.ACKNOWLEDGED));

        Outcome outcome = writer.raise(
                product(), RiskTypes.PENALTY_CAP, Severity.HIGH, "扣分小計 24.0", NOW);

        assertThat(outcome).isEqualTo(Outcome.SUPPRESSED);
        verify(repository, never()).save(any());
    }

    /** 去重窗必須由傳入的偵測時刻回推，不能由查詢自己取 now。 */
    @Test
    @DisplayName("去重窗以傳入的偵測時刻回推 7 日")
    void windowIsSevenDaysBeforeDetection() {
        when(repository.findWithinDedupWindow(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());

        writer.raise(product(), RiskTypes.LOW_CONFIDENCE, Severity.LOW, "信心度 44（門檻 50）", NOW);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findWithinDedupWindow(
                eq(1L), eq(RiskTypes.LOW_CONFIDENCE), since.capture(), any());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-09-28T06:30:00Z"));
    }

    @Test
    @DisplayName("觸發值超過欄位長度 100 時截斷，不讓資料庫擲出例外")
    void truncatesLongTriggerValue() {
        when(repository.findWithinDedupWindow(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());

        writer.raise(product(), RiskTypes.REVIEW_RISK, Severity.MEDIUM, "字".repeat(150), NOW);

        ArgumentCaptor<RiskAlert> saved = ArgumentCaptor.forClass(RiskAlert.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTriggerValue()).hasSize(RiskAlertWriter.TRIGGER_VALUE_MAX_LENGTH);
        assertThat(saved.getValue().getTriggerValue()).endsWith("…");
    }

    @Test
    @DisplayName("未知的示警類型在寫入前就擋下，而不是撞資料庫 CHECK")
    void rejectsUnknownRiskType() {
        assertThatThrownBy(() -> writer.raise(
                product(), "SUPPLIER_ANOMALY", Severity.LOW, "x", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    /** 去重是先查再寫，沒有鎖的話兩條路徑同時進來會各開一筆 OPEN。 */
    @Test
    @DisplayName("查詢前先對（品項, 類型）取鎖")
    void locksBeforeQuerying() {
        when(repository.findWithinDedupWindow(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());

        writer.raise(product(), RiskTypes.HEAT_CRASH, Severity.HIGH, "7 日熱度斜率 -46.0%", NOW);

        InOrder order = inOrder(locks, repository);
        order.verify(locks).lock(1L, RiskTypes.HEAT_CRASH);
        order.verify(repository).findWithinDedupWindow(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("未知類型在取鎖之前就被擋下")
    void unknownTypeDoesNotTakeLock() {
        assertThatThrownBy(() -> writer.raise(product(), "NOPE", Severity.LOW, "x", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verify(locks, never()).lock(anyLong(), anyString());
    }
}
