package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

public record TrendCompositeSnapshot(
        BigDecimal compositeValue,
        BigDecimal slope7d,
        BigDecimal slope30d,
        String stage,              // RISING / PLATEAU / DECLINING
        int stageWeeks,
        Integer estimatedLifespanDays,
        String appliedWeightsJson, // {"THREADS": 0.44, "GOOGLE_TRENDS": 0.28, ...}
        boolean divergenceFlag) {
            
        }