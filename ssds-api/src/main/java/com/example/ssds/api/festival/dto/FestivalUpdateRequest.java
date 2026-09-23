package com.example.ssds.api.festival.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FestivalUpdateRequest(
        @NotBlank @Size(max = 50) String festivalName,
        LocalDate festivalDate,
        Integer lunarMonth,
        Integer lunarDay) {
}
