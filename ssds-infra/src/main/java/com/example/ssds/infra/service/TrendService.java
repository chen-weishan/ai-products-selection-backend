package com.example.ssds.infra.service;

import com.example.ssds.core.dto.TrendDetailResponse;
import com.example.ssds.core.dto.TrendDetailResponse.SourceDetail;

import com.example.ssds.core.dto.TrendChartProjection;
import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.core.dto.TrendSourceDetailProjection;
import com.example.ssds.core.dto.TrendCompositeProjection;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
         // 1. 查真實關鍵字名稱（沿用既有的 findById，繼承自 JpaRepository）
        String keywordName = trendKeywordRepository.findById(keywordId)
                .map(tk -> tk.getKeyword())
                .orElseThrow(() -> new IllegalArgumentException("找不到關鍵字 id=" + keywordId));
        response.setKeyword("keywordName"); 

         // 2. 查各來源明細（含各自 slope7d/slope30d，AC-06-1）
        List<TrendSourceDetailProjection> rawDetails =
                trendKeywordRepository.findSourceDetailsByKeywordId(keywordId);

        // 先算所有可用來源的原始權重總和，用來重新正規化（AC-06-2）
        double totalRawWeight = rawDetails.stream()
                .mapToDouble(TrendSourceDetailProjection::getRawActualWeight)
                .sum();

        List<SourceDetail> details = new ArrayList<>();
        for (TrendSourceDetailProjection raw : rawDetails) {
            SourceDetail detail = new SourceDetail();
            detail.setSourceName(raw.getSourceName());
            detail.setPercentile(raw.getPercentile());
            detail.setStatus(raw.getStatus());
            detail.setSlope7d(raw.getSlope7d());
            detail.setSlope30d(raw.getSlope30d());

            double actualWeight = (totalRawWeight > 0)
                    ? raw.getRawActualWeight() / totalRawWeight
                    : 0;
            detail.setActualWeight(Math.round(actualWeight * 10000.0) / 10000.0);

            details.add(detail);
        }
        response.setSourceDetails(details);

        // 3. 合成後整體今日熱度與斜率
        TrendCompositeProjection composite = trendKeywordRepository.findCompositeByKeywordId(keywordId);
        double heatToday = composite.getHeatToday().doubleValue();
        double slope7d = composite.getSlope7d().doubleValue();
        double slope30d = composite.getSlope30d().doubleValue();

        response.setHeatToday(Math.round(heatToday * 100.0) / 100.0);
        response.setSlope7d(slope7d);
        response.setSlope30d(slope30d);

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