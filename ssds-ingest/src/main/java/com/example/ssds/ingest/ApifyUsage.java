package com.example.ssds.ingest;

/**
 * Apify 帳號本月（當前計費週期）平台用量快照，單位為<b>美分</b>（USD cent）。
 *
 * <p>用美分而不是美元，是因為 {@code heat_source.quota_used／quota_limit} 是整數欄位，
 * 美元會把 $0.43 這類用量四捨五入成 0。前端顯示時請除以 100。
 *
 * @param usedCents  本月已用金額（{@code current.monthlyUsageUsd}）
 * @param limitCents 本月金額上限（{@code limits.maxMonthlyUsageUsd}）；API 未回傳時為 null，
 *                   {@code HeatSource} 會視為「無明確上限」
 */
public record ApifyUsage(int usedCents, Integer limitCents) {
}
