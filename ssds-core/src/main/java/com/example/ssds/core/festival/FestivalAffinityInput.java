package com.example.ssds.core.festival;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FestivalAffinityInput(
        Long festivalId,
        String festivalCode,
        String festivalName,
        LocalDate festivalDate,
        BigDecimal affinity) {

}
