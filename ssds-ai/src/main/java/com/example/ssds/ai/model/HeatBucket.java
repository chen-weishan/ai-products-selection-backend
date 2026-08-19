package com.example.ssds.ai.model;

import java.math.BigDecimal;

/** 七日快取使用的熱度區間；避免同品項熱度顯著變化後仍沿用舊判定。 */
public enum HeatBucket {
    UNKNOWN,
    VERY_LOW,
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH;

    public static HeatBucket fromPercentile(BigDecimal percentile) {
        if (percentile == null) return UNKNOWN;
        if (percentile.compareTo(BigDecimal.valueOf(20)) < 0) return VERY_LOW;
        if (percentile.compareTo(BigDecimal.valueOf(40)) < 0) return LOW;
        if (percentile.compareTo(BigDecimal.valueOf(60)) < 0) return MEDIUM;
        if (percentile.compareTo(BigDecimal.valueOf(80)) < 0) return HIGH;
        return VERY_HIGH;
    }
}
