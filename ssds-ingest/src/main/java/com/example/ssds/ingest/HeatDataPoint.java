package com.example.ssds.ingest;

import java.math.BigDecimal;

/**
 * 單筆熱度來源原始讀值。
 *
 * @param target   來源目標識別（如 Instagram 的 hashtag）
 * @param rawValue 原始熱度值（如 Instagram 抓回的貼文篇數）
 */
public record HeatDataPoint(String target, BigDecimal rawValue) {}