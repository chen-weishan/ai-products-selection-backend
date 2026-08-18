package com.example.ssds.core.domain;

/** AI 洞察類型（規格書 §7.2 ai_insight.insight_type）。 */
public enum InsightType {
    /** 賣點萃取（SellingPointAgent） */
    SELLING_POINT,
    /** 風險分析（ReviewRiskAgent） */
    RISK,
    /** 進貨建議（RecommendationAgent） */
    RECOMMENDATION,
    /** 趨勢解讀（TrendInterpreterAgent） */
    TREND
}
