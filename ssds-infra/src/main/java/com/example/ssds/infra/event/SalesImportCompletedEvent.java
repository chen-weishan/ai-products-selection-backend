package com.example.ssds.infra.event;

/** 銷售資料批次成功寫入後發布；API 層於交易提交後重算受影響品項。 */
public record SalesImportCompletedEvent(Long importBatchId) {}
