package com.example.ssds.api.heat.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** S-16 熱度來源狀態（規格書 FR-14-2）：來源名稱、Adapter 類型、粒度、合成權重、額度用量、最後更新、可用性狀態。 */
public record HeatSourceDetailResponse(
        Long id,
        String sourceCode,
        String adapterType,
        String granularity,
        BigDecimal compositeWeight,
        String availability,
        int quotaUsed,
        Integer quotaLimit,
        Instant lastFetchedAt,
        Instant lastProbedAt,
        short consecutiveProbeFailures,
        boolean enabled) {
}
