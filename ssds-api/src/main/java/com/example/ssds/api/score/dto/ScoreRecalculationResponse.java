package com.example.ssds.api.score.dto;

import com.example.ssds.core.domain.LastScoringStatus;

/** 單一品項純評分重算結果；不建立 AI task。 */
public record ScoreRecalculationResponse(
        Long productId,
        LastScoringStatus status,
        Long primaryScoreId,
        String message) {}
