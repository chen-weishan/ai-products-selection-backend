package com.example.ssds.controller;

import com.example.ssds.core.dto.TrendChartProjection;
import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.core.dto.TrendDetailResponse;
import com.example.ssds.infra.service.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 趨勢分析 API 介面層
@RestController
@RequestMapping("/trends")
public class TrendController {

    private final TrendService trendService;


    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

// 取得所有趨勢訊號
    @GetMapping
    public List<TrendSignalProjection> getTrends() {
        return trendService.getAllTrendSignals();
    }


// 取得單一關鍵字的 90 天歷史熱度折線圖
    @GetMapping("/{keywordId}/chart")
    public List<TrendChartProjection> getTrendChart(@PathVariable Long keywordId) {
        return trendService.getTrendChart(keywordId);
    }

// 取得單一關鍵字趨勢明細
    @GetMapping("/{keywordId}/detail")
    public TrendDetailResponse getTrendDetail(@PathVariable Long keywordId) {
        return trendService.getTrendDetail(keywordId);
    }
}