package com.example.ssds.ai.model;

import java.util.List;

public record SourcingScoutOutput(
        String report,
        List<String> opportunitySignals,
        List<String> riskSignals) {}
