package com.example.ssds.infra.service;

import com.example.ssds.core.dto.TrendDetailResponse;
import com.example.ssds.core.dto.TrendDetailResponse.SourceDetail;

import com.example.ssds.core.dto.TrendChartProjection;
import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;


// 趨勢分析商業邏輯層

@Service
public class TrendService {

    private final TrendKeywordRepository trendKeywordRepository;


    public TrendService(TrendKeywordRepository trendKeywordRepository) {
        this.trendKeywordRepository = trendKeywordRepository;
    }

    // 取得所有趨勢關鍵字的 7日/30日 斜率與 AI 輔助訊號

    @Transactional(readOnly = true)
    public List<TrendSignalProjection> getAllTrendSignals() {
        
        return trendKeywordRepository.findTrendSignals();
    }

    // 取得單一關鍵字近 90 天的歷史熱度 (畫折線圖用)

    @Transactional(readOnly = true)
    public List<TrendChartProjection> getTrendChart(Long keywordId) {
        return trendKeywordRepository.findTrendChartByKeywordId(keywordId); 
    }

    // 取得關鍵字趨勢明細 
    @Transactional(readOnly = true)
    public TrendDetailResponse getTrendDetail(Long keywordId) {
        
        TrendDetailResponse response = new TrendDetailResponse();
        // 實務上這一步會去資料庫撈出關鍵字名稱，這裡先做示範
        response.setKeyword("測試關鍵字"); 

        List<SourceDetail> details = new ArrayList<>();
        
        // --- 模擬從資料庫撈出的預設權重設定 ---
        double threadsOriginalWeight = 0.6;
        double googleOriginalWeight = 0.4;
        
        // 假設 Threads 今天 API 當機 (狀態變成 DEGRADED)
        boolean isThreadsDegraded = true; 
        
        double threadsActualWeight = isThreadsDegraded ? 0.0 : threadsOriginalWeight;
        double googleActualWeight = googleOriginalWeight;
        
        // 重新正規化權重 (若 Threads 掛了，Google Trends 權重會被放大補滿 100%)
        double totalValidWeight = threadsActualWeight + googleActualWeight;
        threadsActualWeight = (totalValidWeight > 0) ? (threadsActualWeight / totalValidWeight) : 0;
        googleActualWeight = (totalValidWeight > 0) ? (googleActualWeight / totalValidWeight) : 0;

        // 裝載 Threads 明細
        SourceDetail threadsDetail = new SourceDetail();
        threadsDetail.setSourceName("THREADS");
        threadsDetail.setPercentile(85.0); // 假設原本拿到 85 分
        threadsDetail.setActualWeight(threadsActualWeight);
        threadsDetail.setStatus(isThreadsDegraded ? "DEGRADED" : "AVAILABLE");
        details.add(threadsDetail);

        // 裝載 Google Trends 明細
        SourceDetail googleDetail = new SourceDetail();
        googleDetail.setSourceName("GOOGLE_TRENDS");
        googleDetail.setPercentile(70.0);
        googleDetail.setActualWeight(googleActualWeight);
        googleDetail.setStatus("AVAILABLE");
        details.add(googleDetail);

        response.setSourceDetails(details);

        // 計算今日合成總分並四捨五入
        double heatToday = (85.0 * threadsActualWeight) + (70.0 * googleActualWeight);
        response.setHeatToday(Math.round(heatToday * 100.0) / 100.0);

        // 計算斜率與背離訊號
        double heat7d = 50.0;  // 假設 7 天前總分是 50
        double heat30d = 90.0; // 假設 30 天前總分是 90

        // 斜率公式: (今天 - 過去) / max(過去, 0.01)
        double slope7d = (heatToday - heat7d) / Math.max(heat7d, 0.01); 
        double slope30d = (heatToday - heat30d) / Math.max(heat30d, 0.01);
        
        response.setSlope7d(Math.round(slope7d * 10000.0) / 100.0);
        response.setSlope30d(Math.round(slope30d * 10000.0) / 100.0);

        // 判斷 AI 輔助標記
        if (slope7d < 0 && slope30d > 0) {
            response.setAiSignal("⚠️ 可能見頂");
        } else if (slope7d > 0 && slope30d < 0) {
            response.setAiSignal("🔥 觸底反彈");
        } else if (slope7d > 0 && slope30d > 0) {
            response.setAiSignal("🚀 持續上升");
        } else {
            response.setAiSignal("📉 持續衰退");
        }

        return response;
    }
}