package com.example.ssds.core.domain;

/**
 * 節慶曆別（規格書 §7.2 festival_calendar.calendar_type）。
 *
 * <p>AC-17-1：LUNAR 的國曆日期須自動換算，不可逐年人工輸入，
 * 因此 festival_date 是換算後的結果，而非使用者輸入值。
 */
public enum CalendarType {
    LUNAR,
    SOLAR
}
