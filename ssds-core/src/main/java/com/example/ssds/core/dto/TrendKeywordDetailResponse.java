package com.example.ssds.core.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * FR-06 趨勢分析：單一關鍵字詳情頁（折線圖 + 各來源權重明細）。
 *
 * <p>取代原本分開的 TrendChartProjection（折線）與 TrendDetailResponse（明細）——
 * 規格書 §8 的 {@code GET /trends/series} 本來就是「合成熱度數列，含各來源明細與
 * 實際合成權重」一支端點，兩者不該分開查。
 */
public class TrendKeywordDetailResponse {

    private Long keywordId;
    private String keyword;

    /** 近 N 天合成熱度折線（區間由呼叫端的 range 參數決定）。 */
    private List<Point> points;

    // --- 今日合成快照，直接讀自 heat_composite_daily，不在查詢時重算 ---
    private BigDecimal heatToday;
    private BigDecimal slope7d;
    private BigDecimal slope30d;

    /** RISING / PLATEAU / DECLINING（§FR-06 統一詞彙）。 */
    private String stage;

    /** 已處於該階段的週數。 */
    private Integer stageWeeks;

    /** 預估剩餘壽命（天）；資料不足時可能為 null。 */
    private Integer estimatedLifespanDays;

    /** 7 日與 30 日斜率背離（可能見頂），AC-06-4。 */
    private boolean divergenceFlag;

    /** 各來源明細，AC-06-1。 */
    private List<SourceDetail> sourceDetails;

    public Long getKeywordId() { return keywordId; }
    public void setKeywordId(Long keywordId) { this.keywordId = keywordId; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public List<Point> getPoints() { return points; }
    public void setPoints(List<Point> points) { this.points = points; }

    public BigDecimal getHeatToday() { return heatToday; }
    public void setHeatToday(BigDecimal heatToday) { this.heatToday = heatToday; }

    public BigDecimal getSlope7d() { return slope7d; }
    public void setSlope7d(BigDecimal slope7d) { this.slope7d = slope7d; }

    public BigDecimal getSlope30d() { return slope30d; }
    public void setSlope30d(BigDecimal slope30d) { this.slope30d = slope30d; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public Integer getStageWeeks() { return stageWeeks; }
    public void setStageWeeks(Integer stageWeeks) { this.stageWeeks = stageWeeks; }

    public Integer getEstimatedLifespanDays() { return estimatedLifespanDays; }
    public void setEstimatedLifespanDays(Integer estimatedLifespanDays) { this.estimatedLifespanDays = estimatedLifespanDays; }

    public boolean isDivergenceFlag() { return divergenceFlag; }
    public void setDivergenceFlag(boolean divergenceFlag) { this.divergenceFlag = divergenceFlag; }

    public List<SourceDetail> getSourceDetails() { return sourceDetails; }
    public void setSourceDetails(List<SourceDetail> sourceDetails) { this.sourceDetails = sourceDetails; }

    /** 折線圖單點。 */
    public record Point(LocalDate date, BigDecimal compositeValue) {}

    /** 單一來源的明細（AC-06-1：斜率、百分位、狀態分列顯示，不合併為黑盒數值）。 */
    public static class SourceDetail {
        private String sourceName;
        private BigDecimal percentile;
        private String status;          // AVAILABLE / DEGRADED / UNAVAILABLE
        private boolean categoryLevel;  // AC-06-5：品類級來源標示
        private BigDecimal appliedWeight; // 本次實際採用的合成權重（來自 applied_weights JSON）
        private BigDecimal slope7d;
        private BigDecimal slope30d;

        public SourceDetail() {}

        public SourceDetail(String sourceName, BigDecimal percentile, String status,
                             boolean categoryLevel, BigDecimal appliedWeight,
                             BigDecimal slope7d, BigDecimal slope30d) {
            this.sourceName = sourceName;
            this.percentile = percentile;
            this.status = status;
            this.categoryLevel = categoryLevel;
            this.appliedWeight = appliedWeight;
            this.slope7d = slope7d;
            this.slope30d = slope30d;
        }

        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }

        public BigDecimal getPercentile() { return percentile; }
        public void setPercentile(BigDecimal percentile) { this.percentile = percentile; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public boolean isCategoryLevel() { return categoryLevel; }
        public void setCategoryLevel(boolean categoryLevel) { this.categoryLevel = categoryLevel; }

        public BigDecimal getAppliedWeight() { return appliedWeight; }
        public void setAppliedWeight(BigDecimal appliedWeight) { this.appliedWeight = appliedWeight; }

        public BigDecimal getSlope7d() { return slope7d; }
        public void setSlope7d(BigDecimal slope7d) { this.slope7d = slope7d; }

        public BigDecimal getSlope30d() { return slope30d; }
        public void setSlope30d(BigDecimal slope30d) { this.slope30d = slope30d; }
    }
}