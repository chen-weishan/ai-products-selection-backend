package com.example.ssds.api.scene.dto;

import com.example.ssds.api.score.dto.ScoreRecalculationResponse;

/** 人工覆寫紀錄與同交易完成的正式分數重算結果。 */
public record SceneOverrideResponse(
        SceneLogResponse scene,
        ScoreRecalculationResponse recalculation) {}
