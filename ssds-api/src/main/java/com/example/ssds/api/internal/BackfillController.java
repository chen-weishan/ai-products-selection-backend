package com.example.ssds.api.internal;

import com.example.ssds.api.schedule.GoogleTrendsBackfillService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一次性回補用：手動觸發 Google Trends 歷史資料 backfill。
 *
 * <p><b>跑完即刪</b>——沒有任何權限保護，且每次呼叫都會消耗 Apify 額度。
 * 比照 {@code IngestTestController} 的做法，只給本機開發用。
 */
@RestController
@RequestMapping("/internal/backfill")
class BackfillController {

    private final GoogleTrendsBackfillService backfillService;

    BackfillController(GoogleTrendsBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    /** 例：POST /internal/backfill/google-trends/1?timeframe=today%2012-m */
    @PostMapping("/google-trends/{keywordId}")
    void backfillGoogleTrends(
            @PathVariable Long keywordId,
            @RequestParam(defaultValue = "today 12-m") String timeframe) {
        backfillService.backfillKeyword(keywordId, timeframe);
    }
}
