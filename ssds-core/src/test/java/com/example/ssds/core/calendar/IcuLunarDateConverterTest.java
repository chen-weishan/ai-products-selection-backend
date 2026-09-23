package com.example.ssds.core.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * AC-17-1：農曆節慶日期須自動換算，不需逐年人工維護。
 *
 * <p>期望值取自 {@code V900__seed_master_data.sql} 既有的 2026 年 LUNAR 檔期，
 * 那份假資料是人工輸入的國曆日期，正好可以當換算結果的對照組。
 */
class IcuLunarDateConverterTest {

    private final LunarDateConverter converter = new IcuLunarDateConverter();

    @ParameterizedTest(name = "{3}：{0} 年農曆 {1}/{2}")
    @CsvSource({
            "2026, 1,  1, 農曆年, 2026-02-17",
            "2026, 1, 15, 元宵,   2026-03-03",
            "2026, 5,  5, 端午,   2026-06-19",
            "2026, 8, 15, 中秋,   2026-09-25",
    })
    void convertsSeededLunarFestivals(
            int year, int lunarMonth, int lunarDay, String name, LocalDate expected) {
        assertEquals(expected, converter.toSolar(year, lunarMonth, lunarDay), name);
    }

    /** 跨年度也要算得出來，這正是「不逐年人工輸入」的意思。 */
    @Test
    void convertsFutureYearsWithoutManualTable() {
        assertEquals(LocalDate.of(2027, 9, 15), converter.toSolar(2027, 8, 15));
        assertEquals(LocalDate.of(2028, 10, 3), converter.toSolar(2028, 8, 15));
    }

    @Test
    void rejectsOutOfRangeInput() {
        assertThrows(IllegalArgumentException.class, () -> converter.toSolar(2026, 13, 1));
        assertThrows(IllegalArgumentException.class, () -> converter.toSolar(2026, 1, 31));
    }
}
