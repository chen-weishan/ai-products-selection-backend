package com.example.ssds.ai.model;

import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Severity;
import java.math.BigDecimal;

public record ReviewTopicStatistic(
        ReviewRiskTopic topic,
        BigDecimal ratio,
        Severity severity
) {}
