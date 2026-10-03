package com.example.ssds.api.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ssds.api.common.error.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CalibrationQuarterTest {

    @Test
    void parsesAndFormats() {
        assertThat(CalibrationQuarter.parse("2026Q3")).isEqualTo(new CalibrationQuarter(2026, 3));
        assertThat(CalibrationQuarter.parse("2026Q3").toString()).isEqualTo("2026Q3");
    }

    @Test
    void rejectsMalformedQuarter() {
        for (String bad : new String[] {null, "2026Q5", "2026q3", "26Q1", "2026-Q3"}) {
            assertThatThrownBy(() -> CalibrationQuarter.parse(bad)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void previousOfFirstQuarterRollsBackAYear() {
        assertThat(CalibrationQuarter.of(LocalDate.of(2027, 1, 1)).previous()).isEqualTo(new CalibrationQuarter(2026, 4));
        assertThat(CalibrationQuarter.of(LocalDate.of(2026, 10, 1)).previous()).isEqualTo(new CalibrationQuarter(2026, 3));
    }

    @Test
    void quarterEndsAtTaipeiMidnight() {
        // 2026Q3 截止 = 2026-10-01 00:00 台北 = 2026-09-30 16:00 UTC
        assertThat(CalibrationQuarter.parse("2026Q3").endExclusive()).isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
        assertThat(CalibrationQuarter.parse("2026Q4").endExclusive()).isEqualTo(Instant.parse("2026-12-31T16:00:00Z"));
    }
}
