package com.example.ssds.api.festival;

import com.example.ssds.api.festival.dto.FestivalWindowStatus;
import com.example.ssds.core.festival.FestivalWindow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * S-20 清單「狀態」欄的判定。
 *
 * <p>時間窗權重是「品項×品類」層級的（前置天數逐品類不同），但清單一列只有一個節慶，
 * 因此取<b>所有關聯品類中窗權重最高者</b>：只要有任何一個品類進入黃金備貨期，
 * 這個檔期對採購就是「該動了」。規格書沒有規定清單狀態怎麼取，這是設計決定。
 *
 * <p>抽成獨立類別而非留在 Service：查詢與命令兩支 Service 都要用同一套判定，
 * 各寫一份必然會分岔。
 */
public final class FestivalWindowStatusResolver {

    private static final BigDecimal GOLDEN = new BigDecimal("1.00");
    private static final BigDecimal SUPPLEMENTARY = new BigDecimal("0.50");

    private FestivalWindowStatusResolver() {
        throw new AssertionError("工具類別，不應被實例化");
    }

    /**
     * @param categoryIds              該節慶關聯到的品類；空集合代表沒有品項關聯此節慶
     * @param leadTimeDaysByCategoryId 品類前置天數查表，缺漏的品類直接略過
     * @return 沒有任何關聯品類時回 {@code NOT_STARTED}：沒有品項要備貨，就沒有備貨期可言
     */
    public static FestivalWindowStatus resolve(
            LocalDate evaluationDate,
            LocalDate festivalDate,
            Set<Long> categoryIds,
            Map<Long, Integer> leadTimeDaysByCategoryId) {

        long daysUntilFestival = ChronoUnit.DAYS.between(evaluationDate, festivalDate);
        if (daysUntilFestival < 0) {
            return FestivalWindowStatus.PASSED;
        }

        BigDecimal best = categoryIds.stream()
                .map(leadTimeDaysByCategoryId::get)
                .filter(Objects::nonNull)
                .map(leadTimeDays -> FestivalWindow.weight(daysUntilFestival, leadTimeDays))
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        if (best.compareTo(GOLDEN) == 0) {
            return FestivalWindowStatus.IN_WINDOW;
        }
        if (best.compareTo(SUPPLEMENTARY) == 0) {
            return FestivalWindowStatus.SUPPLEMENTARY;
        }
        return FestivalWindowStatus.NOT_STARTED;
    }
}
