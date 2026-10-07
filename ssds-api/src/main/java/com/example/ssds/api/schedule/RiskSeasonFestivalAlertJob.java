package com.example.ssds.api.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.ssds.api.risk.RiskFestivalAlertService;
import com.example.ssds.api.risk.RiskSeasonAlertService;

/** 每日補掃季節不匹配與即將關閉的節慶檔期示警。 */
@Component
@ConditionalOnProperty(name = "ssds.risk.season-festival-alert.schedule-enabled", havingValue = "true", matchIfMissing = true)
public class RiskSeasonFestivalAlertJob {

    private static final Logger log = LoggerFactory.getLogger(RiskSeasonFestivalAlertJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final RiskSeasonAlertService seasonService;
    private final RiskFestivalAlertService festivalService;

    public RiskSeasonFestivalAlertJob(
            RiskSeasonAlertService seasonService, RiskFestivalAlertService festivalService) {
        this.seasonService = seasonService;
        this.festivalService = festivalService;
    }

    @Scheduled(cron = "${ssds.risk.season-festival-alert.cron:0 35 6 * * *}", zone = "Asia/Taipei")
    public void run() {
        Instant detectedAt = Instant.now();
        LocalDate today = LocalDate.now(TAIPEI);
        try {
            seasonService.detect(detectedAt);
        } catch (RuntimeException exception) {
            log.error("每日季節不匹配示警補掃失敗", exception);
        }
        try {
            festivalService.detect(today, detectedAt);
        } catch (RuntimeException exception) {
            log.error("每日節慶檔期示警補掃失敗", exception);
        }
    }
}