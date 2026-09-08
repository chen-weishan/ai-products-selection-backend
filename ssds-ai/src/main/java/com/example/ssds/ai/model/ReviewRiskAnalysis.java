package com.example.ssds.ai.model;

import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Sentiment;

public record ReviewRiskAnalysis(
        Long reviewId,
        Sentiment sentiment,
        ReviewRiskTopic riskTopic
) {}
