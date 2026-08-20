package com.example.ssds.controller;

import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.infra.service.TrendService;
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


    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

    /**
     * 取得所有趨勢訊號
     */
    @GetMapping
    public List<TrendSignalProjection> getTrends() {
        return trendService.getAllTrendSignals();
    }
}