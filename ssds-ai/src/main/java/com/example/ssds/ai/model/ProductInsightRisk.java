package com.example.ssds.ai.model;

import com.example.ssds.core.domain.InsightRiskType;
import com.example.ssds.core.domain.Severity;

public record ProductInsightRisk(
        String text,
        InsightRiskType type,
        Severity severity,
        boolean countedInPenalty) {}
