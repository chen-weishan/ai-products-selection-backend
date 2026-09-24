package com.example.ssds.core.climate;

import java.math.BigDecimal;

public record TemperatureRange(BigDecimal min, BigDecimal max, BigDecimal tolerance) {
    public TemperatureRange {
        if (min.compareTo(max) > 0) throw new IllegalArgumentException("min 不可大於 max");
        if (tolerance.signum() <= 0) throw new IllegalArgumentException("tolerance 必須為正");
    }
}
