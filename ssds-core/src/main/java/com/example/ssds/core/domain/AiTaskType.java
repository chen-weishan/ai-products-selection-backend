package com.example.ssds.core.domain;

/**
 * AI 任務類型（規格書 FR-07、§6.3 Agent 規格）。
 *
 * <p>budgetPool() 對應 FR-07 的預算池分離：
 * B 軌探索耗盡預算時不得影響 A 軌批次評分（AC-07-2），
 * 因此配額必須依池別分開計算，不能只看總量。
 */
public enum AiTaskType {

    /** 品項批次完整分析，建立後由 FR-07 執行器拆解各 Agent 工作。 */
    FULL_ANALYSIS(BudgetPool.TRACK_A),
    /** 情境判定（SceneClassifierAgent） */
    SCENE_CLASSIFY(BudgetPool.TRACK_A),
    /** 評論風險分析（ReviewRiskAgent） */
    REVIEW_RISK(BudgetPool.TRACK_A),
    /** 賣點萃取（SellingPointAgent） */
    SELLING_POINT(BudgetPool.TRACK_A),
    /** 進貨建議（RecommendationAgent） */
    RECOMMENDATION(BudgetPool.TRACK_A),
    /** 趨勢解讀（TrendInterpreterAgent） */
    TREND_INTERPRET(BudgetPool.TRACK_A),
    /** 尋源探索（SourcingScoutAgent，透過 MCP 呼叫） */
    SOURCING_SCOUT(BudgetPool.TRACK_B),
    /** 權重校準解讀（WeightCalibrationAgent） */
    WEIGHT_CALIBRATION(BudgetPool.CALIBRATION);

    /** 預算池別（FR-07）。 */
    public enum BudgetPool {
        /** A 軌批次：達 100% 時封鎖，改用上期快取結果並標示 */
        TRACK_A,
        /** B 軌探索：達 100% 時停用探索功能，不影響 A 軌 */
        TRACK_B,
        /** 校準：獨立計費，單獨列示 */
        CALIBRATION
    }

    private final BudgetPool budgetPool;

    AiTaskType(BudgetPool budgetPool) {
        this.budgetPool = budgetPool;
    }

    public BudgetPool budgetPool() {
        return budgetPool;
    }
}
