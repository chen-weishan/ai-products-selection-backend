package com.example.ssds.ai.model;

import com.example.ssds.core.domain.HeatStage;
import java.util.List;

public record SourcingScoutOutput(
        String report,
        List<String> opportunitySignals,
        List<String> riskSignals,
        HeatStage heatStage) {}
