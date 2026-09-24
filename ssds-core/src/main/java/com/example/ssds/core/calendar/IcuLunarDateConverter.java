package com.example.ssds.core.calendar;

import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.ChineseCalendar;
import com.ibm.icu.util.TimeZone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.springframework.stereotype.Component;

/**
 * 以 ICU4J 的 {@link ChineseCalendar} 實作農曆換算（AC-17-1）。
 *
 * <p><b>選型理由</b>（規格書 §3.2 要求 W1 選型，此為實際決定）：
 * <ul>
 *   <li>純 Java、無原生相依，Maven Central 上由 Unicode 官方維護</li>
 *   <li>處理閏月：農曆閏月會讓同一個月份在該年出現兩次，
 *       ICU 以獨立的 {@code IS_LEAP_MONTH} 欄位區分，不必自己查萬年曆</li>
 *   <li>年份範圍遠超過需求的 1900–2100</li>
 *   <li>Unicode License，可商用</li>
 * </ul>
 * 否決的選項：自行以查表法實作（閏月與大小月要逐年維護，正是 AC-17-1 要避免的事）。
 *
 * <p><b>ICU 的兩個陷阱</b>：
 * <ul>
 *   <li>{@code EXTENDED_YEAR} 不是西元年，換算式為「西元年 + 2637」</li>
 *   <li>{@code MONTH} 從 0 起算，農曆正月是 0</li>
 * </ul>
 *
 * <p>農曆年跨在兩個西元年之間，因此農曆十一、十二月換算出來可能落在下一個西元年。
 * 本類別照實回傳，是否接受由呼叫端判斷（{@code festival_calendar} 有
 * {@code ck_festival_year} 約束，寫入前必須自己檢查）。
 */
@Component
public class IcuLunarDateConverter implements LunarDateConverter {

    /** ICU 的農曆紀年與西元年的差。 */
    private static final int EXTENDED_YEAR_OFFSET = 2637;

    /** 規格書 §3.2：系統時區統一 Asia/Taipei。農曆的日界也依此判定。 */
    private static final String ZONE_ID = "Asia/Taipei";

    @Override
    public LocalDate toSolar(int year, int lunarMonth, int lunarDay) {
        if (lunarMonth < 1 || lunarMonth > 12) {
            throw new IllegalArgumentException("農曆月必須介於 1–12：" + lunarMonth);
        }
        if (lunarDay < 1 || lunarDay > 30) {
            throw new IllegalArgumentException("農曆日必須介於 1–30：" + lunarDay);
        }

        ChineseCalendar calendar = new ChineseCalendar(TimeZone.getTimeZone(ZONE_ID));
        calendar.clear();
        // 非寬鬆模式：不存在的日期（如小月的三十）直接丟例外，而不是悄悄順延到隔天
        calendar.setLenient(false);
        calendar.set(Calendar.EXTENDED_YEAR, year + EXTENDED_YEAR_OFFSET);
        calendar.set(Calendar.MONTH, lunarMonth - 1);
        calendar.set(ChineseCalendar.IS_LEAP_MONTH, 0);
        calendar.set(Calendar.DAY_OF_MONTH, lunarDay);

        Date instant;
        try {
            instant = calendar.getTime();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "西元 %d 年的農曆 %d 月 %d 日不存在".formatted(year, lunarMonth, lunarDay), e);
        }

        return Instant.ofEpochMilli(instant.getTime())
                .atZone(ZoneId.of(ZONE_ID))
                .toLocalDate();
    }
}
