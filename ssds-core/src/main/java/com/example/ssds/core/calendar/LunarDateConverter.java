package com.example.ssds.core.calendar;

import java.time.LocalDate;

public interface LunarDateConverter {
    /**
     * 農曆 → 國曆。
     * @param year       國曆年（換算結果落在此年）
     * @param lunarMonth 農曆月 1–12
     * @param lunarDay   農曆日 1–30
     * @return 國曆日期
     * @throws IllegalArgumentException 該年該農曆日期不存在
     */
    LocalDate toSolar(int year, int lunarMonth, int lunarDay);
}
