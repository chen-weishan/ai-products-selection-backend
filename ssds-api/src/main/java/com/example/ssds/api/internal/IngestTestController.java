package com.example.ssds.api.internal;

// import com.example.ssds.schedule.ThreadsHeatIngestJob;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssds.api.schedule.InstagramHeatIngestJob;

/**
 * 開發測試用：手動觸發熱度採集排程，不用乾等 cron 時間到
 * （Instagram 是每週一才跑）。
 *
 * <p><b>只給自己本機開發測試用，正式上線前務必移除這支或加權限保護</b>——
 * 目前沒有任何驗證機制，任何打得到這台機器的人都能觸發，會消耗
 * RapidAPI 的請求額度（Instagram 免費方案總共只有 100 次，見
 * InstagramHeatIngestJob 的排程註解）。
 */
@RestController
@RequestMapping("/internal/ingest")
class IngestTestController {

    private final InstagramHeatIngestJob instagramJob;
    // private final ThreadsHeatIngestJob threadsJob;
    // private final GoogleTrendsHeatIngestJob googleTrendsJob;

    IngestTestController(
            InstagramHeatIngestJob instagramJob)
            // ThreadsHeatIngestJob threadsJob,
            // GoogleTrendsHeatIngestJob googleTrendsJob) 
            {
        this.instagramJob = instagramJob;
        // this.threadsJob = threadsJob;
        // this.googleTrendsJob = googleTrendsJob;
    }

    @PostMapping("/instagram")
    void runInstagram() {
        instagramJob.run();
    }

    // @PostMapping("/threads")
    // void runThreads() {
    //     threadsJob.run();
    // }

    // @PostMapping("/google-trends")
    // void runGoogleTrends() {
    //     googleTrendsJob.run();
    // }
}   