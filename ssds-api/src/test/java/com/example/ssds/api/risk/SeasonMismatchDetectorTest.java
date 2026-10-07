package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ssds.api.risk.SeasonMismatchDetector.Detection;
import com.example.ssds.api.risk.SeasonMismatchDetector.ProductClimate;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Severity;

/** §FR-10-1 SEASON_MISMATCH：百分位 &lt; 門檻且狀態為 ADOPTED／LISTED。 */
class SeasonMismatchDetectorTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("20");

    private final SeasonMismatchDetector detector = new SeasonMismatchDetector();

    private static ProductClimate product(long id, ProductStatus status, String percentile, boolean available) {
        return new ProductClimate(id, 1L, status, percentile == null ? null : new BigDecimal(percentile), available);
    }

    @Test
    @DisplayName("百分位 12 < 20 且 ADOPTED：開立 MEDIUM，觸發值與 dev 假資料同格式")
    void triggersBelowThreshold() {
        Detection detection = detector
                .evaluate(product(1, ProductStatus.ADOPTED, "12", true), THRESHOLD)
                .orElseThrow();

        assertThat(detection.riskType()).isEqualTo("SEASON_MISMATCH");
        assertThat(detection.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(detection.triggerValue()).isEqualTo("季節氣候適配百分位 12（門檻 20）");
    }

    @Test
    @DisplayName("LISTED 同樣觸發；觸發值不帶多餘的零（12.50 → 12.5、門檻 20.00 → 20）")
    void listedTriggersAndFormatsPlainly() {
        Detection detection = detector
                .evaluate(product(1, ProductStatus.LISTED, "12.50", true), new BigDecimal("20.00"))
                .orElseThrow();

        assertThat(detection.triggerValue()).isEqualTo("季節氣候適配百分位 12.5（門檻 20）");
    }

    @Test
    @DisplayName("邊界：恰為 20 不觸發（嚴格小於），19.99 觸發")
    void boundaryIsExclusive() {
        assertThat(detector.evaluate(product(1, ProductStatus.ADOPTED, "20.00", true), THRESHOLD)).isEmpty();
        assertThat(detector.evaluate(product(1, ProductStatus.ADOPTED, "19.99", true), THRESHOLD)).isPresent();
    }

    @Test
    @DisplayName("百分位 0 也是有效值，會觸發（不能把 0 當成沒資料）")
    void zeroPercentileTriggers() {
        assertThat(detector.evaluate(product(1, ProductStatus.ADOPTED, "0", true), THRESHOLD)).isPresent();
    }

    @Test
    @DisplayName("狀態不是 ADOPTED／LISTED：不觸發")
    void otherStatusesDoNotTrigger() {
        for (ProductStatus status : ProductStatus.values()) {
            boolean expected = status == ProductStatus.ADOPTED || status == ProductStatus.LISTED;
            assertThat(detector.evaluate(product(1, status, "5", true), THRESHOLD).isPresent())
                    .as(status.name()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("因子無資料（data_available=false 或百分位 null）：不觸發")
    void noDataDoesNotTrigger() {
        assertThat(detector.evaluate(product(1, ProductStatus.ADOPTED, "5", false), THRESHOLD)).isEmpty();
        assertThat(detector.evaluate(product(1, ProductStatus.ADOPTED, null, true), THRESHOLD)).isEmpty();
    }

    @Test
    @DisplayName("批次：依品類門檻判定，並分別計入狀態略過與無資料略過")
    void batchUsesPerCategoryThresholdAndCountsSkips() {
        List<ProductClimate> products = List.of(
                new ProductClimate(1, 10L, ProductStatus.ADOPTED, new BigDecimal("25"), true), // 品類 10 門檻 30 → 觸發
                new ProductClimate(2, 20L, ProductStatus.ADOPTED, new BigDecimal("25"), true), // 品類 20 門檻 20 → 不觸發
                new ProductClimate(3, 10L, ProductStatus.WATCHING, new BigDecimal("1"), true),  // 狀態略過
                new ProductClimate(4, 10L, ProductStatus.LISTED, null, false));                 // 無資料略過

        SeasonMismatchDetector.Result result =
                detector.detect(products, categoryId -> categoryId == 10L ? new BigDecimal("30") : THRESHOLD);

        assertThat(result.detections()).extracting(Detection::productId).containsExactly(1L);
        assertThat(result.skippedStatus()).isEqualTo(1);
        assertThat(result.skippedNoData()).isEqualTo(1);
    }
}
