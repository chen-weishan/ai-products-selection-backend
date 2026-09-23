package com.example.ssds.api.schedule;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/** 應用在每日主排程時間後才啟動時，依當日合成完整度決定是否補跑。 */
@Component
@ConditionalOnProperty(
        name = "ssds.calibration.heat-catch-up-enabled",
        havingValue = "true")
public class HeatCompositeCatchUp {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositeCatchUp.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final HeatCompositeCalibrationJob calibrationJob;
    private final ObjectProvider<ThreadsHeatIngestJob> threadsIngestJobProvider;
    private final ObjectProvider<GoogleTrendsHeatIngestJob> googleTrendsIngestJobProvider;
    private final ObjectProvider<InstagramHeatIngestJob> instagramIngestJobProvider;
    private final TrendKeywordRepository keywordRepository;
    private final HeatCompositeDailyRepository compositeRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final CronExpression dailySchedule;
    private final CronExpression instagramSchedule;
    private final Clock clock;

    @Autowired
    public HeatCompositeCatchUp(
            HeatCompositeCalibrationJob calibrationJob,
            ObjectProvider<ThreadsHeatIngestJob> threadsIngestJobProvider,
            ObjectProvider<GoogleTrendsHeatIngestJob> googleTrendsIngestJobProvider,
            ObjectProvider<InstagramHeatIngestJob> instagramIngestJobProvider,
            TrendKeywordRepository keywordRepository,
            HeatCompositeDailyRepository compositeRepository,
            HeatReadingRepository heatReadingRepository,
            @Value("${ssds.calibration.heat-composite.cron:0 0 6 * * *}") String dailyCron,
            @Value("${ssds.ingest.instagram.cron:0 30 3 * * MON}") String instagramCron) {
        this(
                calibrationJob,
                threadsIngestJobProvider,
                googleTrendsIngestJobProvider,
                instagramIngestJobProvider,
                keywordRepository,
                compositeRepository,
                heatReadingRepository,
                CronExpression.parse(dailyCron),
                CronExpression.parse(instagramCron),
                Clock.system(BUSINESS_ZONE));
    }

    HeatCompositeCatchUp(
            HeatCompositeCalibrationJob calibrationJob,
            ObjectProvider<ThreadsHeatIngestJob> threadsIngestJobProvider,
            ObjectProvider<GoogleTrendsHeatIngestJob> googleTrendsIngestJobProvider,
            ObjectProvider<InstagramHeatIngestJob> instagramIngestJobProvider,
            TrendKeywordRepository keywordRepository,
            HeatCompositeDailyRepository compositeRepository,
            HeatReadingRepository heatReadingRepository,
            CronExpression dailySchedule,
            CronExpression instagramSchedule,
            Clock clock) {
        this.calibrationJob = calibrationJob;
        this.threadsIngestJobProvider = threadsIngestJobProvider;
        this.googleTrendsIngestJobProvider = googleTrendsIngestJobProvider;
        this.instagramIngestJobProvider = instagramIngestJobProvider;
        this.keywordRepository = keywordRepository;
        this.compositeRepository = compositeRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.dailySchedule = dailySchedule;
        this.instagramSchedule = instagramSchedule;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpAfterStartup() {
        catchUp(ZonedDateTime.now(clock).withZoneSameInstant(BUSINESS_ZONE));
    }

    void catchUp(ZonedDateTime now) {
        LocalDate businessDate = now.toLocalDate();
        ZonedDateTime startOfDay = businessDate.atStartOfDay(BUSINESS_ZONE);
        LocalDate weekStart = businessDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        InstagramHeatIngestJob instagramIngestJob = instagramIngestJobProvider.getIfAvailable();
        ZonedDateTime instagramScheduledAt = instagramSchedule.next(
                weekStart.atStartOfDay(BUSINESS_ZONE).minusNanos(1));
        boolean instagramCatchUpDue = instagramIngestJob != null
                && instagramScheduledAt != null
                && !now.isBefore(instagramScheduledAt)
                && !heatReadingRepository.existsBySourceSourceCodeAndReadingDateBetween(
                        HeatSourceCode.INSTAGRAM, weekStart, weekEnd);
        if (instagramCatchUpDue) {
            log.info("本週尚無 Instagram 熱度資料，開始補跑：weekStart={}", weekStart);
            instagramIngestJob.run();
        } else if (instagramIngestJob != null
                && instagramScheduledAt != null
                && !now.isBefore(instagramScheduledAt)) {
            log.info("本週 Instagram 熱度資料已存在，不需補跑：weekStart={}", weekStart);
        }

        ZonedDateTime dailyScheduledAt = dailySchedule.next(startOfDay.minusNanos(1));
        if (dailyScheduledAt == null
                || !businessDate.equals(dailyScheduledAt.toLocalDate())
                || now.isBefore(dailyScheduledAt)) {
            return;
        }

        long enabledKeywords = keywordRepository.countByEnabledTrue();
        if (enabledKeywords == 0) {
            return;
        }
        List<Long> missingKeywordIds =
                compositeRepository.findEnabledKeywordIdsMissingStatDate(businessDate);
        long completedKeywords = enabledKeywords - missingKeywordIds.size();
        boolean dailyCatchUpDue = !missingKeywordIds.isEmpty();
        if (!dailyCatchUpDue && !instagramCatchUpDue) {
            log.info(
                    "每日熱度主流程今日已完成，不需補跑：date={}, completed={}/{}",
                    businessDate,
                    completedKeywords,
                    enabledKeywords);
            return;
        }

        if (dailyCatchUpDue) {
            log.info(
                    "偵測到每日熱度主流程漏跑或未完整，開始補跑：date={}, completed={}/{}",
                    businessDate,
                    completedKeywords,
                    enabledKeywords);
            ThreadsHeatIngestJob threadsIngestJob = threadsIngestJobProvider.getIfAvailable();
            if (threadsIngestJob != null) {
                threadsIngestJob.runForKeywordIds(missingKeywordIds, businessDate);
            }
            GoogleTrendsHeatIngestJob googleTrendsIngestJob =
                    googleTrendsIngestJobProvider.getIfAvailable();
            if (googleTrendsIngestJob != null) {
                googleTrendsIngestJob.runForKeywordIds(missingKeywordIds, businessDate);
            }
        }
        calibrationJob.runCatchUp(businessDate, missingKeywordIds);
    }
}
