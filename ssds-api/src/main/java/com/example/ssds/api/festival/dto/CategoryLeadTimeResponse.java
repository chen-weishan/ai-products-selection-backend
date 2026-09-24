package com.example.ssds.api.festival.dto;

public record CategoryLeadTimeResponse(
        Long categoryId,
        String categoryName,
        int leadTimeDays) {

}
