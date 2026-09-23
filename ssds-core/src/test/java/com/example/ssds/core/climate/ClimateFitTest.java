package com.example.ssds.core.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** §FR-17-2 的 fit 公式。 */
class ClimateFitTest {

    private static final TemperatureRange SNACK = new TemperatureRange(
            new BigDecimal("18.0"), new BigDecimal("26.0"), new BigDecimal("12.0"));

    /** 規格書 §FR-17-2 的黃金案例：抹茶夾心餅乾、8 月台北 29.4°C。 */
    @Test
    void goldenCase() {
        assertEquals(0, ClimateFit.fit(new BigDecimal("29.4"), SNACK)
                .compareTo(new BigDecimal("0.717")));
    }

    @ParameterizedTest(name = "{2}：T={0} → {1}")
    @CsvSource({
            "18.0, 1.000, 剛好碰到下限也算完全適配",
            "26.0, 1.000, 剛好碰到上限也算完全適配",
            "22.0, 1.000, 區間內",
            "29.4, 0.717, 太熱 3.4 度",
            "14.6, 0.717, 太冷 3.4 度，距離是對稱的",
            "38.0, 0.000, 超出容忍範圍 12 度整，正好歸零",
            "45.0, 0.000, 再遠也不會變負數",
    })
    void fitByTemperature(BigDecimal avgTemp, BigDecimal expected, String description) {
        assertEquals(0, ClimateFit.fit(avgTemp, SNACK).compareTo(expected), description);
    }

    /**
     * distance 是「離區間多遠」，不是區間本身的寬度。
     *
     * <p>把 {@code max − min} 當成 distance 是最容易犯的錯：區間寬度不隨溫度變動，
     * 於是不管幾度都會得到同一個分數。這支測試就是釘死這件事。
     */
    @Test
    void distanceDependsOnTemperatureNotRangeWidth() {
        BigDecimal near = ClimateFit.fit(new BigDecimal("27.0"), SNACK);
        BigDecimal far = ClimateFit.fit(new BigDecimal("33.0"), SNACK);
        org.junit.jupiter.api.Assertions.assertTrue(near.compareTo(far) > 0,
                "離區間越遠分數必須越低，實際 near=" + near + " far=" + far);
    }
}
