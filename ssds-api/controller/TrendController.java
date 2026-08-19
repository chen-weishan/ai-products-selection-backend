package com.example.ssds.api.controller;

import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.core.service.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 趨勢分析 API 介面層
 */
@RestController
@RequestMapping("/api/v1/trends")
public class TrendController {

    private final TrendService trendService;

    // 服務生 (Controller) 呼叫主廚 (Service)
    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

    /**
     * 取得所有趨勢訊號
     */
    @GetMapping
    public List<TrendSignalProjection> getTrends() {
        // 接到客人點單，直接轉交給主廚處理，並把結果當作 JSON 端出去
        return trendService.getAllTrendSignals();
    }
}