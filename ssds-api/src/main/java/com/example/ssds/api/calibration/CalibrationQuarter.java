package com.example.ssds.api.calibration;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 校準季度（{@code calibration_report.quarter}，格式 {@code 2026Q3}，§7.2.11）。
 *
 * <p>季度邊界以 Asia/Taipei 判定，與 §FR-11 結案日、§7.2.6 評分週次一致。
 */
record CalibrationQuarter(int year, int quarter) {

    static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    static CalibrationQuarter parse(String value) {
        if (value == null || !value.matches("\\d{4}Q[1-4]")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "季度格式必須為 YYYYQ1～YYYYQ4，例如 2026Q3");
        }
        return new CalibrationQuarter(Integer.parseInt(value.substring(0, 4)), value.charAt(5) - '0');
    }

    static CalibrationQuarter of(LocalDate date) {
        return new CalibrationQuarter(date.getYear(), (date.getMonthValue() - 1) / 3 + 1);
    }

    CalibrationQuarter previous() {
        return quarter == 1 ? new CalibrationQuarter(year - 1, 4) : new CalibrationQuarter(year, quarter - 1);
    }

    LocalDate firstDay() {
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }

    /** 下一季第一天 00:00（台北）——樣本截止點，不含。 */
    Instant endExclusive() {
        return firstDay().plusMonths(3).atStartOfDay(ZONE).toInstant();
    }

    @Override
    public String toString() {
        return year + "Q" + quarter;
    }
}
