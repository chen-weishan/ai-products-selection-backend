package com.example.ssds.core.dto;

import java.util.List;

/**
 * FR-06: 趨勢分析明細餐盒 (打破黑盒，包含各來源明細與實際權重)
 */
public class TrendDetailResponse {

    // --- 1. 總體趨勢與 AI 訊號 (原有的資料) ---
    private String keyword;
    private Double heatToday;
    private Double slope7d;
    private Double slope30d;
    private String aiSignal;

    // --- 2. 打破黑盒：各來源明細清單 (AC-06-1, AC-06-2) ---
    private List<SourceDetail> sourceDetails;

    // 內部類別：單一來源的明細數據
    public static class SourceDetail {
        private String sourceName;    // 來源名稱 (如 THREADS, GOOGLE_TRENDS)
        private Double percentile;    // 同源百分位 (各來源內部正規化後的分數)
        private Double actualWeight;  // 本次實際採用的合成比例 (降級重算後的真實權重)
        private String status;        // 可用性狀態 (AVAILABLE / DEGRADED / UNAVAILABLE)
        private Double slope7d;
        private Double slope30d;

        // --- SourceDetail 的 Getter & Setter ---
        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }

        public Double getPercentile() { return percentile; }
        public void setPercentile(Double percentile) { this.percentile = percentile; }

        public Double getActualWeight() { return actualWeight; }
        public void setActualWeight(Double actualWeight) { this.actualWeight = actualWeight; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Double getSlope7d() { return slope7d; }
        public void setSlope7d(Double slope7d) { this.slope7d = slope7d; }

        public Double getSlope30d() { return slope30d; }
        public void setSlope30d(Double slope30d) { this.slope30d = slope30d; }
    }

    // --- TrendDetailResponse 的 Getter & Setter ---
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public Double getHeatToday() { return heatToday; }
    public void setHeatToday(Double heatToday) { this.heatToday = heatToday; }

    public Double getSlope7d() { return slope7d; }
    public void setSlope7d(Double slope7d) { this.slope7d = slope7d; }

    public Double getSlope30d() { return slope30d; }
    public void setSlope30d(Double slope30d) { this.slope30d = slope30d; }

    public String getAiSignal() { return aiSignal; }
    public void setAiSignal(String aiSignal) { this.aiSignal = aiSignal; }

    public List<SourceDetail> getSourceDetails() { return sourceDetails; }
    public void setSourceDetails(List<SourceDetail> sourceDetails) { this.sourceDetails = sourceDetails; }
}