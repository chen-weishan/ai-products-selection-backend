package com.example.ssds.ai.model;

import com.example.ssds.core.domain.HeatStage;

public record TrendInterpreterOutput(
        HeatStage stage,
        int stageWeeks,
        int estimatedLifespanDays) {}
