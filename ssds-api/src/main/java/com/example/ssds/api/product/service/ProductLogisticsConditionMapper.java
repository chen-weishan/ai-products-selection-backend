package com.example.ssds.api.product.service;

import com.example.ssds.core.domain.LogisticsCondition;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 在 API 的物流條件集合與既有 VARCHAR 欄位之間轉換。 */
final class ProductLogisticsConditionMapper {

    private ProductLogisticsConditionMapper() {
    }

    static String encode(Set<LogisticsCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return null;
        }
        return conditions.stream()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    static Set<LogisticsCondition> decode(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return Set.of();
        }

        EnumSet<LogisticsCondition> result = EnumSet.noneOf(LogisticsCondition.class);
        String normalized = storedValue.toUpperCase(Locale.ROOT);
        Arrays.stream(normalized.split("[,|｜]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(value -> addKnownCode(result, value));

        // 相容既有開發資料中的中文自由文字；新資料一律寫入英文代碼。
        if (storedValue.contains("常溫")) {
            result.add(LogisticsCondition.NORMAL);
        }
        if (storedValue.contains("冷藏") || storedValue.contains("冷鏈")) {
            result.add(LogisticsCondition.CHILLED);
        }
        if (storedValue.contains("冷凍")) {
            result.add(LogisticsCondition.FROZEN);
        }
        if (storedValue.contains("易碎")) {
            result.add(LogisticsCondition.FRAGILE);
        }
        if (storedValue.contains("易融化")) {
            result.add(LogisticsCondition.MELTABLE);
        }
        if (storedValue.contains("材積大") || storedValue.contains("重量大")) {
            result.add(LogisticsCondition.OVERSIZED);
        }
        return result.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(result));
    }

    private static void addKnownCode(
            EnumSet<LogisticsCondition> result,
            String value
    ) {
        try {
            result.add(LogisticsCondition.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            // 舊資料可能含「夏季易融化」等說明，交由下方中文相容規則處理。
        }
    }
}
