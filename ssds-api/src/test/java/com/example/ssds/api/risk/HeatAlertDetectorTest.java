package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ssds.api.risk.HeatAlertDetector.Detection;
import com.example.ssds.api.risk.HeatAlertDetector.ProductHeat;
import com.example.ssds.api.risk.HeatAlertDetector.Result;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Severity;

/** §FR-10-2 熱度異常判定：HEAT_CRASH 與 HEAT_SURGE 的每個邊界。 */
class HeatAlertDetectorTest {

    private static final Function<Long, BigDecimal> CRASH_DEFAULT = id -> new BigDecimal("-0.40");
    private static final Function<Long, BigDecimal> SURGE_DEFAULT = id -> new BigDecimal("0.95");

    private final HeatAlertDetector detector = new HeatAlertDetector();

    private static ProductHeat heat(long id, long category, Long parent, ProductStatus status,
            String slope, boolean belowFloor) {
        return new ProductHeat(id, category, parent, status, 900 + id, "kw" + id, new BigDecimal(slope), belowFloor);
    }

    private static ProductHeat adopted(long id, String slope) {
        return heat(id, 1L, null, ProductStatus.ADOPTED, slope, false);
    }

    /** 同一品類 n 個品項，斜率 0.01、0.02、…（遞增），id 與排名相同。 */
    private static List<ProductHeat> category(long categoryId, Long parent, int count, long firstId, boolean floor) {
        List<ProductHeat> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(heat(firstId + i - 1, categoryId, parent, ProductStatus.WATCHING,
                    new BigDecimal("0.01").multiply(BigDecimal.valueOf(i)).toPlainString(), floor));
        }
        return list;
    }

    private Result detect(List<ProductHeat> products) {
        return detector.detect(products, CRASH_DEFAULT, SURGE_DEFAULT);
    }

    private static List<Long> ids(Result result, String type) {
        return result.detections().stream()
                .filter(d -> d.riskType().equals(type))
                .map(Detection::productId)
                .toList();
    }

    // ---- HEAT_CRASH ----

    @Test
    @DisplayName("HEAT_CRASH：恰為 −40% 會觸發（≤），嚴重度 HIGH")
    void crashBoundaryInclusive() {
        Result result = detect(List.of(adopted(1, "-0.4000")));

        assertThat(result.detections()).hasSize(1);
        Detection d = result.detections().get(0);
        assertThat(d.riskType()).isEqualTo("HEAT_CRASH");
        assertThat(d.severity()).isEqualTo(Severity.HIGH);
        assertThat(d.triggerValue()).isEqualTo("7 日熱度斜率 -40.0%（門檻 -40.0%）；關鍵字：kw1");
    }

    @Test
    @DisplayName("HEAT_CRASH：−39.99% 不觸發")
    void crashJustAboveThreshold() {
        assertThat(detect(List.of(adopted(1, "-0.3999"))).detections()).isEmpty();
    }

    @Test
    @DisplayName("HEAT_CRASH 只看 ADOPTED／LISTED：觀察中、草稿、已淘汰都不觸發")
    void crashOnlyForAdoptedOrListed() {
        List<ProductHeat> products = List.of(
                heat(1, 1L, null, ProductStatus.ADOPTED, "-0.60", false),
                heat(2, 1L, null, ProductStatus.LISTED, "-0.60", false),
                heat(3, 1L, null, ProductStatus.WATCHING, "-0.60", false),
                heat(4, 1L, null, ProductStatus.EVALUATING, "-0.60", false),
                heat(5, 1L, null, ProductStatus.DRAFT, "-0.60", false),
                heat(6, 1L, null, ProductStatus.REJECTED, "-0.60", false));

        assertThat(ids(detect(products), "HEAT_CRASH")).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("HEAT_CRASH：品類覆寫的門檻生效")
    void crashUsesCategoryThreshold() {
        List<ProductHeat> products = List.of(
                heat(1, 1L, null, ProductStatus.ADOPTED, "-0.30", false),
                heat(2, 2L, null, ProductStatus.ADOPTED, "-0.30", false));
        Function<Long, BigDecimal> crash = id -> id == 1L ? new BigDecimal("-0.25") : new BigDecimal("-0.40");

        Result result = detector.detect(products, crash, SURGE_DEFAULT);

        assertThat(ids(result, "HEAT_CRASH")).containsExactly(1L);
    }

    // ---- HEAT_SURGE ----

    @Test
    @DisplayName("HEAT_SURGE：同品類 10 個品項，只有第 100 百分位那個觸發（MEDIUM）")
    void surgeTopOfTenOnly() {
        Result result = detect(category(1L, null, 10, 1, false));

        assertThat(ids(result, "HEAT_SURGE")).containsExactly(10L);
        Detection d = result.detections().get(0);
        assertThat(d.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(d.triggerValue()).isEqualTo("7 日熱度斜率 +10.0%，同品類第 100 百分位（門檻 P95）；關鍵字：kw10");
    }

    /** 21 個品項時第 2 高的百分位恰為 (20−1)×100/20 = 95.00，是 P95 邊界。 */
    @Test
    @DisplayName("HEAT_SURGE：百分位恰為 95.00 會觸發，94 以下不觸發")
    void surgeBoundaryInclusive() {
        Result result = detect(category(1L, null, 21, 1, false));

        // 第 21 名＝100、第 20 名＝95.00 觸發；第 19 名＝90 不觸發
        assertThat(ids(result, "HEAT_SURGE")).containsExactlyInAnyOrder(21L, 20L);
    }

    @Test
    @DisplayName("HEAT_SURGE：熱度量級不足時不觸發，並計入略過數")
    void surgeSkippedBelowVolumeFloor() {
        List<ProductHeat> products = new ArrayList<>(category(1L, null, 9, 1, false));
        products.add(heat(10, 1L, null, ProductStatus.WATCHING, "0.10", true));

        Result result = detect(products);

        assertThat(ids(result, "HEAT_SURGE")).isEmpty();
        assertThat(result.skippedVolumeFloor()).isEqualTo(1);
    }

    @Test
    @DisplayName("HEAT_SURGE：量級不足的品項仍留在母體裡（分佈是全品類的斜率）")
    void belowFloorProductsStayInPopulation() {
        // 只有最高的那個量級不足；其餘品項的百分位不應因它被拿掉而改變
        List<ProductHeat> products = new ArrayList<>(category(1L, null, 9, 1, false));
        products.add(heat(10, 1L, null, ProductStatus.WATCHING, "0.10", true));

        Result result = detect(products);

        // 第 9 高（0.09）的百分位是 (9−1)×100/9 = 88.89，不到 95，不觸發
        assertThat(ids(result, "HEAT_SURGE")).isEmpty();
    }

    @Test
    @DisplayName("HEAT_SURGE：不要求品項狀態（規格只限制 HEAT_CRASH）")
    void surgeIgnoresStatus() {
        List<ProductHeat> products = new ArrayList<>(category(1L, null, 9, 1, false));
        products.add(heat(10, 1L, null, ProductStatus.REJECTED, "0.10", false));

        assertThat(ids(detect(products), "HEAT_SURGE")).containsExactly(10L);
    }

    @Test
    @DisplayName("HEAT_SURGE：斜率不為正不開立，即使百分位排在最前面")
    void surgeRequiresPositiveSlope() {
        List<ProductHeat> products = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            // −0.50、−0.45、…、−0.05：最高的 −0.05 在品類裡是第 100 百分位，但沒有竄升
            products.add(heat(i, 1L, null, ProductStatus.WATCHING,
                    new BigDecimal("-0.55").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(i))).toPlainString(),
                    false));
        }

        Result result = detect(products);

        assertThat(ids(result, "HEAT_SURGE")).isEmpty();
        assertThat(result.skippedInsufficientSample()).isZero();
    }

    @Test
    @DisplayName("HEAT_SURGE：全品類斜率相同時百分位為 50，不觸發")
    void surgeAllTied() {
        List<ProductHeat> products = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            products.add(heat(i, 1L, null, ProductStatus.WATCHING, "0.50", false));
        }

        assertThat(ids(detect(products), "HEAT_SURGE")).isEmpty();
    }

    @Test
    @DisplayName("HEAT_SURGE：品類覆寫的百分位門檻生效")
    void surgeUsesCategoryPercentile() {
        Function<Long, BigDecimal> surge = id -> new BigDecimal("0.80");

        Result result = detector.detect(category(1L, null, 10, 1, false), CRASH_DEFAULT, surge);

        // n=10：第 10 名 100、第 9 名 88.89、第 8 名 77.78 → P80 以上為前兩名
        assertThat(ids(result, "HEAT_SURGE")).containsExactlyInAnyOrder(10L, 9L);
        assertThat(result.detections().get(0).triggerValue()).contains("門檻 P80");
    }

    // ---- 樣本不足的退路 ----

    @Test
    @DisplayName("同品類 3–9 個且有父品類：與兄弟品類合併後判定，並標明「同父品類合併」")
    void surgeMergesSiblingCategories() {
        List<ProductHeat> products = new ArrayList<>();
        products.addAll(category(1L, 100L, 5, 1, false));   // 斜率 0.01–0.05
        products.addAll(category(2L, 100L, 6, 11, false));  // 斜率 0.01–0.06
        // 合併後 11 個：最高的是 0.06（兄弟品類的第 6 個，id 16），第二高 0.05 兩個並列

        Result result = detect(products);

        assertThat(ids(result, "HEAT_SURGE")).containsExactly(16L);
        assertThat(result.detections().get(0).triggerValue()).contains("同父品類合併第 100 百分位");
    }

    @Test
    @DisplayName("同品類 3–9 個但沒有父品類：略過，不退回全品類或固定斜率")
    void surgeSkippedWithoutParent() {
        Result result = detect(category(1L, null, 5, 1, false));

        assertThat(result.detections()).isEmpty();
        assertThat(result.skippedInsufficientSample()).isEqualTo(5);
    }

    @Test
    @DisplayName("同品類不足 3 個：略過，即使有父品類")
    void surgeSkippedUnderThree() {
        List<ProductHeat> products = new ArrayList<>();
        products.addAll(category(1L, 100L, 2, 1, false));
        products.addAll(category(2L, 100L, 20, 11, false));

        Result result = detect(products);

        // 品類 1 的兩個品項樣本不足被略過；品類 2 有 20 個，只用自己的品類
        assertThat(result.skippedInsufficientSample()).isEqualTo(2);
        assertThat(ids(result, "HEAT_SURGE")).doesNotContain(1L, 2L);
    }

    @Test
    @DisplayName("不同品類的分佈彼此隔離")
    void categoriesAreIsolated() {
        List<ProductHeat> products = new ArrayList<>();
        // 品類 1 的斜率都在 5 以上、品類 2 都在 0.1 以下；品類 2 的第一名不該被品類 1 稀釋
        for (int i = 1; i <= 10; i++) {
            BigDecimal step = new BigDecimal("0.01").multiply(BigDecimal.valueOf(i));
            products.add(heat(i, 1L, null, ProductStatus.WATCHING,
                    new BigDecimal("5").add(step).toPlainString(), false));
            products.add(heat(100 + i, 2L, null, ProductStatus.WATCHING, step.toPlainString(), false));
        }

        Result result = detect(products);

        assertThat(ids(result, "HEAT_SURGE")).containsExactlyInAnyOrder(10L, 110L);
    }

    // ---- 其他 ----

    @Test
    @DisplayName("沒有品項時回傳空結果")
    void empty() {
        Result result = detect(List.of());

        assertThat(result.detections()).isEmpty();
        assertThat(result.skippedInsufficientSample()).isZero();
        assertThat(result.skippedVolumeFloor()).isZero();
    }

    @Test
    @DisplayName("同一品項不會同時觸發 HEAT_CRASH 與 HEAT_SURGE（一個要斜率為負、一個要為正）")
    void crashAndSurgeAreMutuallyExclusive() {
        List<ProductHeat> products = new ArrayList<>(category(1L, null, 9, 1, false));
        products.add(heat(10, 1L, null, ProductStatus.ADOPTED, "0.10", false));
        products.add(heat(11, 1L, null, ProductStatus.ADOPTED, "-0.80", false));

        Result result = detect(products);

        assertThat(ids(result, "HEAT_SURGE")).doesNotContain(11L);
        assertThat(ids(result, "HEAT_CRASH")).containsExactly(11L);
    }

    @Test
    @DisplayName("百分比字串：一位小數，正數帶 +")
    void percentFormatting() {
        assertThat(HeatAlertDetector.percent(new BigDecimal("0.25"))).isEqualTo("+25.0%");
        assertThat(HeatAlertDetector.percent(new BigDecimal("-0.46"))).isEqualTo("-46.0%");
        assertThat(HeatAlertDetector.percent(BigDecimal.ZERO)).isEqualTo("0.0%");
        assertThat(HeatAlertDetector.percent(new BigDecimal("3.4"))).isEqualTo("+340.0%");
    }
}
