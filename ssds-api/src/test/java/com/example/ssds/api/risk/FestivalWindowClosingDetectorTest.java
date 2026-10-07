package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ssds.api.risk.FestivalWindowClosingDetector.Candidate;
import com.example.ssds.api.risk.FestivalWindowClosingDetector.Detection;
import com.example.ssds.core.domain.Severity;

/** §FR-10-1 FESTIVAL_WINDOW_CLOSING：窗權重今天為 1.0，且門檻日數內會降為 0.5 或 0。 */
class FestivalWindowClosingDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 1);
    private static final int LEAD = 21;

    private final FestivalWindowClosingDetector detector = new FestivalWindowClosingDetector();

    /** 節慶日 = 今天 + daysUntilFestival。 */
    private static Candidate candidate(long product, Integer lead, long daysUntilFestival, String affinity) {
        return new Candidate(product, 1L, lead, "MID_AUTUMN", "中秋", TODAY.plusDays(daysUntilFestival),
                new BigDecimal(affinity));
    }

    private FestivalWindowClosingDetector.Result run(int threshold, Candidate... candidates) {
        return detector.detect(List.of(candidates), TODAY, categoryId -> threshold);
    }

    @Test
    @DisplayName("d = L：明天就降為 0.5，觸發 LOW，觸發值帶節慶、天數與前置天數")
    void triggersWhenDropIsTomorrow() {
        Detection detection = run(7, candidate(1, LEAD, LEAD, "0.60")).detections().get(0);

        assertThat(detection.riskType()).isEqualTo("FESTIVAL_WINDOW_CLOSING");
        assertThat(detection.severity()).isEqualTo(Severity.LOW);
        assertThat(detection.triggerValue())
                .isEqualTo("中秋 檔期窗將於 1 日後由 1.0 降為 0.5（節慶日 2026-07-22，前置 21 日，關聯度 0.6）");
    }

    @Test
    @DisplayName("邊界：d = L+6（7 日後降）觸發；d = L+7（8 日後降）不觸發")
    void thresholdBoundaryIsInclusive() {
        assertThat(run(7, candidate(1, LEAD, LEAD + 6, "0.60")).detections()).hasSize(1);
        assertThat(run(7, candidate(1, LEAD, LEAD + 7, "0.60")).detections()).isEmpty();
    }

    @Test
    @DisplayName("今天已經是 0.5（d < L）：已經降過了，不觸發")
    void alreadyHalfWindowDoesNotTrigger() {
        assertThat(run(7, candidate(1, LEAD, LEAD - 1, "0.60")).detections()).isEmpty();
        assertThat(run(7, candidate(1, LEAD, 0, "0.60")).detections()).isEmpty();
    }

    @Test
    @DisplayName("太早（d > L+30，窗權重 0）或節慶已過（d < 0）：不觸發")
    void outsideWindowDoesNotTrigger() {
        assertThat(run(7, candidate(1, LEAD, LEAD + 31, "0.60")).detections()).isEmpty();
        assertThat(run(7, candidate(1, LEAD, -1, "0.60")).detections()).isEmpty();
    }

    @Test
    @DisplayName("前置天數 0：d = 0 是黃金期最後一天，明天直接歸零，觸發值寫「降為 0」")
    void zeroLeadTimeDropsToZero() {
        Detection detection = run(7, candidate(1, 0, 0, "0.50")).detections().get(0);

        assertThat(detection.triggerValue()).contains("1 日後由 1.0 降為 0（");
    }

    @Test
    @DisplayName("門檻日數可調：門檻 3 時 d = L+2 觸發、d = L+3 不觸發")
    void customThreshold() {
        assertThat(run(3, candidate(1, LEAD, LEAD + 2, "0.60")).detections()).hasSize(1);
        assertThat(run(3, candidate(1, LEAD, LEAD + 3, "0.60")).detections()).isEmpty();
    }

    @Test
    @DisplayName("關聯度 0 的節慶不判定")
    void zeroAffinityIsIgnored() {
        assertThat(run(7, candidate(1, LEAD, LEAD, "0.00")).detections()).isEmpty();
    }

    @Test
    @DisplayName("品類沒有前置天數：不假設 0，略過並計入 skippedNoLeadTime（每品項一次）")
    void missingLeadTimeIsSkipped() {
        FestivalWindowClosingDetector.Result result =
                run(7, candidate(1, null, 3, "0.60"), candidate(1, null, 5, "0.60"));

        assertThat(result.detections()).isEmpty();
        assertThat(result.skippedNoLeadTime()).isEqualTo(1);
    }

    @Test
    @DisplayName("同品項多個節慶同時符合：只開一筆，取關聯度最高者；並列取節慶日較早者")
    void oneAlertPerProductHighestAffinityWins() {
        Candidate low = new Candidate(1, 1L, LEAD, "FATHERS_DAY", "父親節", TODAY.plusDays(LEAD), new BigDecimal("0.30"));
        Candidate high = new Candidate(1, 1L, LEAD, "MID_AUTUMN", "中秋", TODAY.plusDays(LEAD + 3), new BigDecimal("0.80"));
        Candidate tieLater = new Candidate(2, 1L, LEAD, "B", "較晚", TODAY.plusDays(LEAD + 3), new BigDecimal("0.50"));
        Candidate tieEarlier = new Candidate(2, 1L, LEAD, "A", "較早", TODAY.plusDays(LEAD + 1), new BigDecimal("0.50"));

        List<Detection> detections = run(7, low, high, tieLater, tieEarlier).detections();

        assertThat(detections).hasSize(2);
        assertThat(detections.get(0).triggerValue()).startsWith("中秋");
        assertThat(detections.get(1).triggerValue()).startsWith("較早");
    }

    @Test
    @DisplayName("同品項另有節慶尚在黃金期中段：只要有一個節慶快關窗就觸發，不被「最大值」遮住")
    void anyClosingFestivalTriggers() {
        Candidate closing = new Candidate(1, 1L, LEAD, "A", "快關窗", TODAY.plusDays(LEAD + 2), new BigDecimal("0.20"));
        Candidate comfortable = new Candidate(1, 1L, LEAD, "B", "還很早", TODAY.plusDays(LEAD + 25), new BigDecimal("0.90"));

        List<Detection> detections = run(7, closing, comfortable).detections();

        assertThat(detections).hasSize(1);
        assertThat(detections.get(0).triggerValue()).startsWith("快關窗");
    }

    @Test
    @DisplayName("門檻依品類查：兩個品類各用各的日數")
    void thresholdPerCategory() {
        Candidate cat1 = new Candidate(1, 1L, LEAD, "X", "甲", TODAY.plusDays(LEAD + 4), new BigDecimal("0.5"));
        Candidate cat2 = new Candidate(2, 2L, LEAD, "X", "乙", TODAY.plusDays(LEAD + 4), new BigDecimal("0.5"));

        FestivalWindowClosingDetector.Result result =
                detector.detect(List.of(cat1, cat2), TODAY, categoryId -> categoryId == 1L ? 7 : 3);

        assertThat(result.detections()).extracting(Detection::productId).containsExactly(1L);
    }
}
