package com.example.ssds.api.internal;

import com.example.ssds.api.schedule.GoogleTrendsHeatIngestJob;
import com.example.ssds.api.schedule.InstagramHeatIngestJob;
import com.example.ssds.api.schedule.ThreadsHeatIngestJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 開發測試用：手動觸發熱度採集排程，不用乾等 cron 時間到
 * （Instagram 是每週一才跑）。
 *
 * <p><b>只給自己本機開發測試用，正式上線前務必移除這支或加權限保護</b>——
 * 目前沒有任何驗證機制，任何打得到這台機器的人都能觸發，會消耗
 * Apify 帳號的用量額度（依實際爬到的貼文數計費，見
 * InstagramHeatIngestJob 的排程註解）。
 */
@RestController
@RequestMapping("/internal/ingest")
class IngestTestController {

    private final ObjectProvider<InstagramHeatIngestJob> instagramJobProvider;
    private final ObjectProvider<ThreadsHeatIngestJob> threadsJobProvider;
    private final ObjectProvider<GoogleTrendsHeatIngestJob> googleTrendsJobProvider;

    IngestTestController(
            ObjectProvider<InstagramHeatIngestJob> instagramJobProvider,
            ObjectProvider<ThreadsHeatIngestJob> threadsJobProvider,
            ObjectProvider<GoogleTrendsHeatIngestJob> googleTrendsJobProvider) {
        this.instagramJobProvider = instagramJobProvider;
        this.threadsJobProvider = threadsJobProvider;
        this.googleTrendsJobProvider = googleTrendsJobProvider;
    }

    @PostMapping("/instagram")
    void runInstagram() {
        requireEnabled(instagramJobProvider, "Instagram").run();
    }

    @PostMapping("/threads")
    void runThreads() {
        requireEnabled(threadsJobProvider, "Threads").run();
    }

    @PostMapping("/google-trends")
    void runGoogleTrends() {
        requireEnabled(googleTrendsJobProvider, "Google Trends").run();
    }

    private static <T> T requireEnabled(ObjectProvider<T> provider, String sourceName) {
        T job = provider.getIfAvailable();
        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, sourceName + " ingest is disabled");
        }
        return job;
    }
}
