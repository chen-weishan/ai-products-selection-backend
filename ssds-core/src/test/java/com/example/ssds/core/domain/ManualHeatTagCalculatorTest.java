package com.example.ssds.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ssds.core.domain.ManualHeatTagCalculator.Observation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FR-14-1-a 人工熱度標記換算規則的單元測試（AC-14-2、AC-14-3、AC-14-7）。
 *
 * <p>純 Java 計算，不需要 Spring 或資料庫（比照 {@link ManualHeatTagCalculator} 類別註解的設計理由）。
 */
class ManualHeatTagCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final int HALVE_AFTER_DAYS = ManualHeatTagCalculator.DEFAULT_HALVE_AFTER_DAYS; // 14
    private static final int EXPIRE_DAYS = ManualHeatTagCalculator.DEFAULT_EXPIRE_DAYS; // 30

    @Nested
    @DisplayName("步驟 1：基礎值換算")
    class BaseValue {

        @Test
        @DisplayName("1～5 等級線性換算為 20～100")
        void mapsLevelToBaseValue() {
            assertThat(ManualHeatTagCalculator.baseValue((short) 1)).isEqualByComparingTo("20");
            assertThat(ManualHeatTagCalculator.baseValue((short) 2)).isEqualByComparingTo("40");
            assertThat(ManualHeatTagCalculator.baseValue((short) 3)).isEqualByComparingTo("60");
            assertThat(ManualHeatTagCalculator.baseValue((short) 4)).isEqualByComparingTo("80");
            assertThat(ManualHeatTagCalculator.baseValue((short) 5)).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("超出 1–5 範圍時建構 Observation 即拋例外")
        void rejectsOutOfRangeLevel() {
            assertThatThrownBy(() -> new Observation((short) 0, NOW, 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Observation((short) 6, NOW, 1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("步驟 2：階梯式時間衰減（AC-14-2）")
    class DecayFactor {

        @Test
        @DisplayName("未滿 14 天權重為 1.0")
        void fullWeightBeforeHalveThreshold() {
            assertThat(ManualHeatTagCalculator.decayFactor(0, HALVE_AFTER_DAYS, EXPIRE_DAYS))
                    .isEqualByComparingTo("1.0");
            assertThat(ManualHeatTagCalculator.decayFactor(13, HALVE_AFTER_DAYS, EXPIRE_DAYS))
                    .isEqualByComparingTo("1.0");
        }

        @Test
        @DisplayName("滿 14 天（含）、未滿 30 天權重降為 0.5")
        void halvedBetweenThresholds() {
            assertThat(ManualHeatTagCalculator.decayFactor(14, HALVE_AFTER_DAYS, EXPIRE_DAYS))
                    .isEqualByComparingTo("0.5");
            assertThat(ManualHeatTagCalculator.decayFactor(29, HALVE_AFTER_DAYS, EXPIRE_DAYS))
                    .isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("滿 30 天（含）完全失效")
        void expiresAtThirtyDays() {
            assertThat(ManualHeatTagCalculator.decayFactor(30, HALVE_AFTER_DAYS, EXPIRE_DAYS))
                    .isEqualByComparingTo("0.0");
            assertThat(ManualHeatTagCalculator.decayFactor(365, HALVE_AFTER_DAYS, EXPIRE_DAYS))
                    .isEqualByComparingTo("0.0");
        }

        @Test
        @DisplayName("採階梯式而非指數半衰：14 天整與 29 天的衰減係數相同")
        void isStepFunctionNotExponentialHalfLife() {
            BigDecimal atFourteen = ManualHeatTagCalculator.decayFactor(14, HALVE_AFTER_DAYS, EXPIRE_DAYS);
            BigDecimal atTwentyNine = ManualHeatTagCalculator.decayFactor(29, HALVE_AFTER_DAYS, EXPIRE_DAYS);
            assertThat(atFourteen).isEqualByComparingTo(atTwentyNine);
        }
    }

    @Nested
    @DisplayName("步驟 4：標記人數信心係數（AC-14-3、AC-14-7）")
    class ConfidenceFactor {

        @Test
        @DisplayName("單人標記係數 0.6")
        void singleTagger() {
            assertThat(ManualHeatTagCalculator.confidenceFactor(1)).isEqualByComparingTo("0.6");
        }

        @Test
        @DisplayName("兩人標記係數 0.8（v2.0 遺漏、v3.0 補齊的一段）")
        void twoTaggers() {
            assertThat(ManualHeatTagCalculator.confidenceFactor(2)).isEqualByComparingTo("0.8");
        }

        @Test
        @DisplayName("三人以上係數 1.0")
        void threeOrMoreTaggers() {
            assertThat(ManualHeatTagCalculator.confidenceFactor(3)).isEqualByComparingTo("1.0");
            assertThat(ManualHeatTagCalculator.confidenceFactor(10)).isEqualByComparingTo("1.0");
        }
    }

    @Nested
    @DisplayName("步驟 1–4 整合：computeRawValue")
    class ComputeRawValue {

        @Test
        @DisplayName("單筆、當天觀察：raw_value = base × 1.0（衰減）× 0.6（單人信心）")
        void singleFreshObservation() {
            Observation obs = new Observation((short) 5, NOW, 1L); // base = 100
            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    List.of(obs), NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            // 100 * 1.0 = 100（加權平均），再乘上單人信心係數 0.6 = 60
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualByComparingTo("60.000");
        }

        @Test
        @DisplayName("同一人多次標記只計一次相異人數（AC-14-7）")
        void sameTaggerCountsOnceForConfidence() {
            Observation first = new Observation((short) 5, NOW, 1L);
            Observation second = new Observation((short) 5, NOW, 1L);

            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    List.of(first, second), NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            // 兩筆都是同一人、同天、同等級：聚合值仍是 100，信心係數仍是單人的 0.6，
            // 不會因為多了一筆標記就被誤判成兩人標記。
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualByComparingTo("60.000");
        }

        @Test
        @DisplayName("三位相異標記人、等級皆為新鮮觀察：信心係數升到 1.0（AC-14-3）")
        void threeDistinctTaggersReachFullConfidence() {
            List<Observation> observations = List.of(
                    new Observation((short) 3, NOW, 1L), // base 60
                    new Observation((short) 3, NOW, 2L), // base 60
                    new Observation((short) 3, NOW, 3L) // base 60
            );

            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    observations, NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            // 聚合值 60（三筆同值、同衰減，加權平均仍是 60），乘上三人信心係數 1.0
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualByComparingTo("60.000");
        }

        @Test
        @DisplayName("多人多等級：以衰減權重加權平均後才乘信心係數")
        void weightedAverageAcrossDifferentLevelsAndAges() {
            Instant fifteenDaysAgo = NOW.minus(15, ChronoUnit.DAYS); // decay = 0.5
            List<Observation> observations = List.of(
                    new Observation((short) 5, NOW, 1L), // base 100, decay 1.0
                    new Observation((short) 1, fifteenDaysAgo, 2L) // base 20, decay 0.5
            );

            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    observations, NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            // aggregated = (100*1.0 + 20*0.5) / (1.0 + 0.5) = 110 / 1.5 = 73.3333
            // raw_value = 73.3333 * confidence(2 人 = 0.8) = 58.6667
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualByComparingTo(new BigDecimal("58.667"));
        }

        @Test
        @DisplayName("全部標記皆已失效（age ≥ 30 天）：回傳空值，當天不寫入 heat_reading")
        void allObservationsExpiredReturnsEmpty() {
            Instant longAgo = NOW.minus(31, ChronoUnit.DAYS);
            Observation obs = new Observation((short) 5, longAgo, 1L);

            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    List.of(obs), NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("已失效的標記不參與聚合，也不計入相異標記人數")
        void expiredObservationsExcludedFromAggregationAndTaggerCount() {
            Instant longAgo = NOW.minus(31, ChronoUnit.DAYS);
            List<Observation> observations = List.of(
                    new Observation((short) 1, longAgo, 1L), // 已失效，應被忽略
                    new Observation((short) 5, NOW, 2L) // 仍有效
            );

            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    observations, NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            // 若失效標記誤入計算，聚合值與信心係數都會不同；
            // 正確結果應等同「只有第二筆有效觀察」的單人案例：100 * 0.6 = 60
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualByComparingTo("60.000");
        }

        @Test
        @DisplayName("空清單直接回傳空值")
        void emptyObservationsReturnsEmpty() {
            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    List.of(), NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("觀察時間晚於評估時間時，age 收斂為 0，不產生負值破壞衰減公式")
        void futureObservedAtClampsAgeToZero() {
            Instant future = NOW.plus(5, ChronoUnit.DAYS);
            Observation obs = new Observation((short) 5, future, 1L);

            Optional<BigDecimal> result = ManualHeatTagCalculator.computeRawValue(
                    List.of(obs), NOW, HALVE_AFTER_DAYS, EXPIRE_DAYS);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualByComparingTo("60.000");
        }
    }
}
