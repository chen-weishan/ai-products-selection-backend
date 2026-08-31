package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

public record SourceBreakdownRow(
        String sourceCode,
        String granularity,
        String availability,
        BigDecimal percentileWithinSource,
        BigDecimal slope7d,
        BigDecimal slope30d) {
        }
