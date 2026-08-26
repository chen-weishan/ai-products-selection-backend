package com.example.ssds.core.dto;

import java.math.BigDecimal;

public interface TrendCompositeProjection {
    BigDecimal getHeatToday();
    BigDecimal getSlope7d();
    BigDecimal getSlope30d();
}