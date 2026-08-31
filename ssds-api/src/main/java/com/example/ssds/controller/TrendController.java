package com.example.ssds.controller;

import com.example.ssds.core.dto.TrendKeywordDetailResponse;
import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.infra.service.TrendService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value="/trends", produces = MediaType.APPLICATION_JSON_VALUE)
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


    // 取得單一關鍵字：折線圖 + 各來源權重明細
    @GetMapping("/{keywordId}")
    public TrendKeywordDetailResponse getKeywordDetail(
            @PathVariable("keywordId") Long keywordId,
            @RequestParam(value = "range", defaultValue = "90d") String range) {
        return trendService.getKeywordDetail(keywordId, range);
    }
}