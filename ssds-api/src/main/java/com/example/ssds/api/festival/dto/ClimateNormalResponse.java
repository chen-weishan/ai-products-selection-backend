package com.example.ssds.api.festival.dto;

import java.math.BigDecimal;

public record ClimateNormalResponse(
        String regionCode,
        short month,
        BigDecimal avgTemp,
        BigDecimal rainProbability) {

}
