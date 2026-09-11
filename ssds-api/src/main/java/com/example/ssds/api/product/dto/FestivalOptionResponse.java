package com.example.ssds.api.product.dto;

/** 品項表單可選的節慶，不重複回傳不同年份的同一節慶。 */
public record FestivalOptionResponse(
        String festivalCode,
        String festivalName
) {
}
