package com.example.ssds.infra.dao.projection;

/**
 * 準確度分析結果（FR-11-3）。
 *
 * <p>{@code sampleSize} 刻意放在第一個欄位：AC-11-5 要求樣本數未達 200 時
 * 畫面必須顯示效度警示，所以任何使用這份資料的地方都得先看到它。
 */
public record AccuracyRow(
        long sampleSize,
        Double scoreQtyCorrelation,
        Double gradeAHitRate,
        Double sceneOverrideRate,
        Double aiFollowRate) {}
