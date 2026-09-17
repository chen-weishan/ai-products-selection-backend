package com.example.ssds.api.schedule;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.ingest.Threads.ThreadsSearchClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性回補用：針對單一歷史日期重跑 Threads 關鍵字熱度採集，補排程當天
 * 沒跑成功而缺漏的 {@code heat_reading}（比照 {@link GoogleTrendsBackfillService}
 * 的用法）。
 *
 * <p>跟 Google Trends 不同，Threads/Apify 這個 actor 沒有真正的「歷史快照」
 * 查詢能力，只能盡量把 start_date/end_date 窗口對齊到目標日期後再用
 * created_at 二次過濾（見 {@link ThreadsSearchClient#fetchEngagementHeatForDate}），
 * 準確度不如當天即時採集。
 */
@Service
public class ThreadsBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ThreadsBackfillService.class);

    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final ThreadsSearchClient client;

    public ThreadsBackfillService(
            TrendKeywordRepository trendKeywordRepository,
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            ThreadsSearchClient client) {
        this.trendKeywordRepository = trendKeywordRepository;
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.client = client;
    }

    /** 補指定日期（例如 2026-09-15）所有啟用中關鍵字的 Threads 熱度。 */
    @Transactional
    public void backfillDate(LocalDate targetDate) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.THREADS)
                .orElseThrow(() -> new IllegalStateException("heat_source 尚未註冊 THREADS 這筆。"));

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        if (keywords.isEmpty()) {
            log.info("沒有啟用中的關鍵字，略過 Threads 回補（{}）。", targetDate);
            return;
        }

        int written = 0;
        for (TrendKeyword keyword : keywords) {
            Long heat;
            try {
                heat = client.fetchEngagementHeatForDate(keyword.getKeyword(), targetDate);
            } catch (Exception e) {
                log.warn("Threads 回補失敗，跳過：{}（{}）", keyword.getKeyword(), targetDate, e);
                continue;
            }
            if (heat == null) {
                log.warn("Threads 回補查無資料，跳過：{}（{}）", keyword.getKeyword(), targetDate);
                continue;
            }
            upsert(source, keyword, targetDate, heat);
            written++;
        }

        log.info("Threads 回補完成（{}）：{} 個關鍵字中，成功寫入 {} 筆。", targetDate, keywords.size(), written);
    }

    /**
     * 補一段日期區間（例如整月）所有啟用中關鍵字的 Threads 熱度。每個關鍵字
     * 只對 Apify 打「一次」請求涵蓋整段區間，再依貼文日期分桶寫入各天，
     * 比對每一天各呼叫一次 {@link #backfillDate} 省額度很多。
     *
     * <p>代價見 {@link ThreadsSearchClient#fetchEngagementHeatByDate}：區間
     * 越長，較舊日期的資料越容易因為 {@code max_posts} 上限被擠掉而缺漏
     * （缺漏的天數不會寫入 heat_reading，之後可以再對那幾天單獨呼叫
     * {@link #backfillDate} 補）。
     */
    @Transactional
    public void backfillRange(LocalDate startDate, LocalDate endDateInclusive) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.THREADS)
                .orElseThrow(() -> new IllegalStateException("heat_source 尚未註冊 THREADS 這筆。"));

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        if (keywords.isEmpty()) {
            log.info("沒有啟用中的關鍵字，略過 Threads 區間回補（{} ~ {}）。", startDate, endDateInclusive);
            return;
        }

        int written = 0;
        for (TrendKeyword keyword : keywords) {
            java.util.Map<LocalDate, Long> byDate;
            try {
                byDate = client.fetchEngagementHeatByDate(keyword.getKeyword(), startDate, endDateInclusive);
            } catch (Exception e) {
                log.warn("Threads 區間回補失敗，跳過：{}（{} ~ {}）", keyword.getKeyword(), startDate, endDateInclusive, e);
                continue;
            }
            for (var entry : byDate.entrySet()) {
                upsert(source, keyword, entry.getKey(), entry.getValue());
                written++;
            }
            if (byDate.size() < java.time.temporal.ChronoUnit.DAYS.between(startDate, endDateInclusive) + 1) {
                log.warn(
                        "Threads 區間回補：{} 在 {} ~ {} 只查到 {} 天有資料，其餘天數可能被 max_posts 上限擠掉，缺漏的天數需另外用 backfillDate 補。",
                        keyword.getKeyword(), startDate, endDateInclusive, byDate.size());
            }
        }

        log.info(
                "Threads 區間回補完成（{} ~ {}）：{} 個關鍵字，共寫入 {} 筆（關鍵字×天）。",
                startDate, endDateInclusive, keywords.size(), written);
    }

    /** 補指定日期、單一關鍵字的 Threads 熱度（挑著補用）。 */
    @Transactional
    public void backfillKeyword(Long keywordId, LocalDate targetDate) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.THREADS)
                .orElseThrow(() -> new IllegalStateException("heat_source 尚未註冊 THREADS 這筆。"));
        TrendKeyword keyword = trendKeywordRepository.getReferenceById(keywordId);

        Long heat = client.fetchEngagementHeatForDate(keyword.getKeyword(), targetDate);
        if (heat == null) {
            log.warn("Threads 回補查無資料，跳過：{}（{}）", keyword.getKeyword(), targetDate);
            return;
        }
        upsert(source, keyword, targetDate, heat);
    }

    /** 補一段日期區間、單一關鍵字的 Threads 熱度（跟 {@link #backfillRange} 一樣只打一次 Apify）。 */
    @Transactional
    public void backfillRangeForKeyword(Long keywordId, LocalDate startDate, LocalDate endDateInclusive) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.THREADS)
                .orElseThrow(() -> new IllegalStateException("heat_source 尚未註冊 THREADS 這筆。"));
        TrendKeyword keyword = trendKeywordRepository.getReferenceById(keywordId);

        java.util.Map<LocalDate, Long> byDate =
                client.fetchEngagementHeatByDate(keyword.getKeyword(), startDate, endDateInclusive);
        for (var entry : byDate.entrySet()) {
            upsert(source, keyword, entry.getKey(), entry.getValue());
        }
        if (byDate.size() < java.time.temporal.ChronoUnit.DAYS.between(startDate, endDateInclusive) + 1) {
            log.warn(
                    "Threads 區間回補：{} 在 {} ~ {} 只查到 {} 天有資料，其餘天數可能被 max_posts 上限擠掉，缺漏的天數需另外用 backfillKeyword 補。",
                    keyword.getKeyword(), startDate, endDateInclusive, byDate.size());
        }
    }

    private void upsert(HeatSource source, TrendKeyword keyword, LocalDate date, Long heat) {
        HeatReading reading = heatReadingRepository
                .findByKeywordIdAndSourceIdAndReadingDate(keyword.getId(), source.getId(), date)
                .orElseGet(() -> HeatReading.builder().source(source).keyword(keyword).readingDate(date).build());
        reading.setRawValue(BigDecimal.valueOf(heat));
        heatReadingRepository.save(reading);
    }
}