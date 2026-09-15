package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

public record TrendSignalRow(
        Long keywordId,
        String keyword,
        BigDecimal heatToday,
        BigDecimal slope7d,
        BigDecimal slope30d,
        String stage,
        boolean divergenceFlag) {
            
        }