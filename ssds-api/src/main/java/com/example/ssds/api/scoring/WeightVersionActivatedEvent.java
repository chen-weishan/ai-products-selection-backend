package com.example.ssds.api.scoring;

/** 權重版本交易提交後觸發純計算全量重評。 */
public record WeightVersionActivatedEvent(Long weightVersionId) {}
