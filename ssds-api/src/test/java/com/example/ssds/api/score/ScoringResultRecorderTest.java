package com.example.ssds.api.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;

/**
 * §5.7 資料不足的兩處承接：{@code product.last_scoring_status}（V23）
 * 與 {@code risk_alert}（V27 的 DATA_INSUFFICIENT）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoringResultRecorderTest {

    private static final Instant ATTEMPTED_AT = Instant.parse("2026-07-22T06:30:00Z");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RiskAlertRepository riskAlertRepository;

    @InjectMocks
    private ScoringResultRecorder recorder;

    private Product product() {
        return ScoreTestFixtures.product(1L, "海鹽奶蓋餅乾");
    }

    private RiskAlert capturedAlert() {
        ArgumentCaptor<RiskAlert> captor = ArgumentCaptor.forClass(RiskAlert.class);
        verify(riskAlertRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("資料不足時同時寫入品項狀態與示警")
    void insufficientDataWritesBothSinks() {
        when(riskAlertRepository
                .findFirstByProductIdAndRiskTypeAndStatusAndDetectedAtAfterOrderByDetectedAtDesc(
                        anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        Product product = product();

        recorder.recordInsufficientData(product, 2, ATTEMPTED_AT);

        assertThat(product.getLastScoringStatus()).isEqualTo(LastScoringStatus.INSUFFICIENT_DATA);
        assertThat(product.getLastScoringAttemptedAt()).isEqualTo(ATTEMPTED_AT);
        assertThat(product.isScoringDataInsufficient()).isTrue();
        verify(productRepository).save(product);

        RiskAlert alert = capturedAlert();
        assertThat(alert.getRiskType())
                .isEqualTo(ScoringResultRecorder.RISK_TYPE_DATA_INSUFFICIENT);
        assertThat(alert.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.getDetectedAt()).isEqualTo(ATTEMPTED_AT);
        // 觸發值要說得出「差在哪」，不是只寫「資料不足」
        assertThat(alert.getTriggerValue()).isEqualTo("可用加分因子 2 項（門檻 3 項）");
    }

    /**
     * FR-10-2 去重：每天跑一次批次、資料一直補不齊的品項，
     * 不去重會在 7 天內堆出 7 筆一模一樣的示警。
     */
    @Test
    @DisplayName("FR-10-2 七日內已有 OPEN 示警時只更新，不新開一筆")
    void reuseOpenAlertWithinDedupWindow() {
        RiskAlert existing = RiskAlert.builder()
                .id(77L)
                .product(product())
                .riskType(ScoringResultRecorder.RISK_TYPE_DATA_INSUFFICIENT)
                .severity(Severity.MEDIUM)
                .status(AlertStatus.OPEN)
                .triggerValue("可用加分因子 1 項（門檻 3 項）")
                .detectedAt(Instant.parse("2026-07-18T06:30:00Z"))
                .build();
        when(riskAlertRepository
                .findFirstByProductIdAndRiskTypeAndStatusAndDetectedAtAfterOrderByDetectedAtDesc(
                        anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.of(existing));

        recorder.recordInsufficientData(product(), 2, ATTEMPTED_AT);

        RiskAlert saved = capturedAlert();
        assertThat(saved.getId()).isEqualTo(77L);
        assertThat(saved.getTriggerValue()).isEqualTo("可用加分因子 2 項（門檻 3 項）");
        assertThat(saved.getDetectedAt()).isEqualTo(ATTEMPTED_AT);
    }

    /** 去重窗的起算點必須是這次嘗試的時刻減 7 日，不能是查詢自己取的 now。 */
    @Test
    @DisplayName("去重窗以傳入的嘗試時刻回推七日")
    void dedupWindowIsSevenDaysBeforeAttempt() {
        when(riskAlertRepository
                .findFirstByProductIdAndRiskTypeAndStatusAndDetectedAtAfterOrderByDetectedAtDesc(
                        anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        recorder.recordInsufficientData(product(), 0, ATTEMPTED_AT);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(riskAlertRepository)
                .findFirstByProductIdAndRiskTypeAndStatusAndDetectedAtAfterOrderByDetectedAtDesc(
                        anyLong(), anyString(), any(), since.capture());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-07-15T06:30:00Z"));
    }

    @Test
    @DisplayName("評分成功時只更新品項狀態，不開示警")
    void scoredDoesNotOpenAlert() {
        Product product = product();

        recorder.recordScored(product, ATTEMPTED_AT);

        assertThat(product.getLastScoringStatus()).isEqualTo(LastScoringStatus.SCORED);
        assertThat(product.isScoringDataInsufficient()).isFalse();
        verify(productRepository).save(product);
        verify(riskAlertRepository, never()).save(any());
    }
}
