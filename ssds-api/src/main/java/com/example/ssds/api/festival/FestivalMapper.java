package com.example.ssds.api.festival;

import com.example.ssds.api.festival.dto.FestivalResponse;
import com.example.ssds.api.festival.dto.FestivalWindowStatus;
import com.example.ssds.infra.entity.FestivalCalendar;

/**
 * entity → 回應 DTO 的轉換。純函式、無狀態，因此是 static 而非 Spring bean。
 *
 * <p>放在 ssds-api 而非 ssds-infra：DTO 是 API 契約的一部分，
 * 讓 infra 認識 DTO 會反轉依賴方向（§3.3）。寫法比照 {@code WeightVersionMapper}。
 */
public final class FestivalMapper {

    private FestivalMapper() {
        throw new AssertionError("工具類別，不應被實例化");
    }

    /**
     * @param relatedCategoryCount 該節慶關聯到的品類數，由 Service 一次撈齊後傳入
     * @param windowStatus         S-20 列表的狀態欄，由 Service 依評估日算出
     */
    public static FestivalResponse toResponse(
            FestivalCalendar festival,
            int relatedCategoryCount,
            FestivalWindowStatus windowStatus) {

        return new FestivalResponse(
                festival.getId(),
                festival.getFestivalCode(),
                festival.getFestivalName(),
                festival.getCalendarType(),
                festival.getFestivalDate(),
                festival.getYear(),
                relatedCategoryCount,
                windowStatus);
    }
}
