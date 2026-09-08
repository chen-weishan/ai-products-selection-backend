package com.example.ssds.core.domain;

/** AI 洞察類型（規格書 §7.2 ai_insight.insight_type）。 */
public enum InsightType {
    /** ProductInsightAgent 的賣點文字 */
    SELLING_POINT,
    /** ProductInsightAgent 的風險文字；逐則評論分類另存 review_analysis。 */
    RISK,
    /** 進貨建議（RecommendationAgent） */
    RECOMMENDATION,
    /** 趨勢解讀（TrendInterpreterAgent） */
    TREND
}
