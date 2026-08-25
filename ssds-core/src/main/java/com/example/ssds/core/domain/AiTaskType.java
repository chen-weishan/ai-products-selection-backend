package com.example.ssds.core.domain;

/**
 * AI 任務類型（規格書 FR-07、§6.3 Agent 規格）。
 *
 * <p>budgetPool() 對應 FR-07 的預算池分離：
 * B 軌探索耗盡預算時不得影響 A 軌批次評分（AC-07-2），
 * 因此配額必須依池別分開計算，不能只看總量。
 */
public enum AiTaskType {

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
    WEIGHT_CALIBRATION(BudgetPool.RETRY);

    /**
     * 預算池別（規格書 §7.2.7 L2930 ai_task.budget_pool、FR-07）。
     *
     * <p>v3.0 統一為三池。校準併入 RETRY 池——季度執行一次、量小，不值得獨立配額
     * （L883）。v2.0 的「校準獨立計費、單獨列示」已被推翻，不要再開第四個池。
     */
    public enum BudgetPool {
        /** A 軌批次 70%：達 100% 時停止送出，未完成品項列入待重跑清單、隔日續跑 */
        TRACK_A,
        /** B 軌探索 20%：達 100% 時停用探索功能，不影響 A 軌（AC-07-2） */
        TRACK_B,
        /** 重試與臨時任務 10%：429 退避重試、單品項手動重算、季度校準 */
        RETRY
    }

    private final BudgetPool budgetPool;

    AiTaskType(BudgetPool budgetPool) {
        this.budgetPool = budgetPool;
    }

    public BudgetPool budgetPool() {
        return budgetPool;
    }
}
