package com.example.ssds.api.festival.dto;

import java.time.LocalDate;

import com.example.ssds.core.domain.CalendarType;

public record FestivalResponse(
        Long id,
        String festivalCode,
        String festivalName,
        CalendarType calendarType,
        LocalDate festivalDate,
        int year,
        int relatedCategoryCount, // S-20 列表「關聯品類」欄
        FestivalWindowStatus windowStatus // S-20 列表「狀態」欄
) {
}
