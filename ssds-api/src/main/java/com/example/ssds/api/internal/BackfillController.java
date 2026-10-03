package com.example.ssds.api.internal;

import com.example.ssds.api.schedule.GoogleTrendsBackfillService;
import com.example.ssds.api.schedule.ThreadsBackfillService;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一次性回補用：手動觸發 Google Trends／Threads 歷史資料 backfill。
 *
 * <p><b>跑完即刪</b>——沒有任何權限保護，且每次呼叫都會消耗 Apify 額度。
 * 比照 {@code IngestTestController} 的做法，只給本機開發用。
 */
@RestController
@RequestMapping("/internal/backfill")
class BackfillController {

    private final GoogleTrendsBackfillService backfillService;
    private final ThreadsBackfillService threadsBackfillService;

    BackfillController(
            GoogleTrendsBackfillService backfillService,
            ThreadsBackfillService threadsBackfillService) {
        this.backfillService = backfillService;
        this.threadsBackfillService = threadsBackfillService;
    }

    /** 例：POST /internal/backfill/google-trends/1?timeframe=today%2012-m */
    @PostMapping("/google-trends/{keywordId}")
    void backfillGoogleTrends(
            @PathVariable Long keywordId,
            @RequestParam(defaultValue = "today 12-m") String timeframe) {
        backfillService.backfillKeyword(keywordId, timeframe);
    }

    /** 例：POST /internal/backfill/threads/2026-09-15 —— 補當天所有啟用中關鍵字。 */
    @PostMapping("/threads/{date}")
    void backfillThreads(@PathVariable String date) {
        threadsBackfillService.backfillDate(LocalDate.parse(date));
    }

    /** 例：POST /internal/backfill/threads-range/2026-09-01/2026-09-30 —— 補整段區間（每關鍵字只打一次 Apify）。 */
    @PostMapping("/threads-range/{startDate}/{endDate}")
    void backfillThreadsRange(@PathVariable String startDate, @PathVariable String endDate) {
        threadsBackfillService.backfillRange(LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    /** 例：POST /internal/backfill/threads-range/2026-09-01/2026-09-30/1 —— 只補關鍵字 id=1 的整段區間。 */
    @PostMapping("/threads-range/{startDate}/{endDate}/{keywordId}")
    void backfillThreadsRangeKeyword(
            @PathVariable String startDate, @PathVariable String endDate, @PathVariable Long keywordId) {
        threadsBackfillService.backfillRangeForKeyword(
                keywordId, LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    /** 例：POST /internal/backfill/threads/2026-09-15/1 —— 只補關鍵字 id=1。 */
    @PostMapping("/threads/{date}/{keywordId}")
    void backfillThreadsKeyword(@PathVariable String date, @PathVariable Long keywordId) {
        threadsBackfillService.backfillKeyword(keywordId, LocalDate.parse(date));
    }
}