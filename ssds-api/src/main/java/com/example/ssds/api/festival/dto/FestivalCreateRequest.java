package com.example.ssds.api.festival.dto;

import java.time.LocalDate;

import com.example.ssds.core.domain.CalendarType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FestivalCreateRequest(
        @NotBlank @Size(max = 32) String festivalCode,
        @NotBlank @Size(max = 50) String festivalName,
        @NotNull CalendarType calendarType,
        @NotNull @Min(1900) @Max(2100) Integer year,
        LocalDate festivalDate, // SOLAR 必填；LUNAR 留空，由換算產生
        @Min(1) @Max(12) Integer lunarMonth, // LUNAR 必填
        @Min(1) @Max(30) Integer lunarDay// LUNAR 必填
) {
}
