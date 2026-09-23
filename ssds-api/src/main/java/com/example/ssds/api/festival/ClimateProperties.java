package com.example.ssds.api.festival;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ssds.climate")
public record ClimateProperties(
        BigDecimal defaultTolerance, // 預設 12.0（§FR-17-2）
        String defaultRegion         // 區域代碼；規格書 §7.2 的預設值與假資料不符，見 application.properties
) {
}
