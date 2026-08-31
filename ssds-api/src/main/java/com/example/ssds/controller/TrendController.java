package com.example.ssds.controller;

import com.example.ssds.core.dto.TrendKeywordDetailResponse;
import com.example.ssds.infra.dao.projection.TrendSignalRow;
import com.example.ssds.infra.service.TrendService;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;

import java.util.List;

@RestController
@RequestMapping("/trends")
public class TrendController {

    private final TrendService trendService;

    public TrendController(TrendService trendService) {
        this.trendService = trendService;
    }

    // 取得所有趨勢訊號
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TrendSignalRow> getTrends() {
        return trendService.getAllTrendSignals();
    }

    // 取得單一關鍵字：折線圖 + 各來源權重明細
    @GetMapping("/{keywordId}")
    public TrendKeywordDetailResponse getKeywordDetail(
            @PathVariable("keywordId") Long keywordId,
            @RequestParam(value = "range", defaultValue = "90d") String range) {
        return trendService.getKeywordDetail(keywordId, range);
    }
}