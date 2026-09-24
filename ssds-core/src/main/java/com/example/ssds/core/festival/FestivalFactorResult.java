package com.example.ssds.core.festival;

import java.math.BigDecimal;

public record FestivalFactorResult(
        BigDecimal rawValue,
        Long drivingFestivalId,
        String drivingFestivalName) {
}
