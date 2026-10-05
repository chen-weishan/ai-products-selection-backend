package com.example.ssds.api.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.ssds.api.risk.RiskHeatAlertService;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;

/**
 * §FR-10-2／§5.10：每日 06:30 熱度異常示警偵測（HEAT_CRASH、HEAT_SURGE），
 * 緊接 06:00 的熱度採集與合成（{@link HeatCompositeCalibrationJob}）之後。不呼叫 LLM。
 *
 * <p>cron 可由環境變數 {@code SCHEDULE_HEAT_ALERT} 覆寫（預設 {@code 0 30 6 * * *}，台北時區）；
 * {@code RISK_HEAT_ALERT_SCHEDULE_ENABLED=false} 可整支關閉。
 *
 * <p>06:00 的合成若還沒跑完或有關鍵字漏寫，06:30 會看到不完整的資料：仍會執行（已有資料的
 * 判定依然有效），但會記警告並帶出缺漏數，讓「今天的 P95 是在殘缺資料上算的」查得到。
 *
 * <p>多節點部署：目前專案沒有接 ShedLock（依賴已在 build.gradle，但沒有 LockProvider
 * 與 {@code shedlock} 資料表），本任務重複執行是安全的——示警寫入有（品項, 類型）鎖與 7 日去重，
 * 第二次只會更新同一筆，不會多開。只是會白跑一次。
 */
@Component
@ConditionalOnProperty(name = "ssds.risk.heat-alert.schedule-enabled", havingValue = "true", matchIfMissing = true)
public class RiskHeatAlertJob {

    private static final Logger log = LoggerFactory.getLogger(RiskHeatAlertJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final RiskHeatAlertService service;
    private final HeatCompositeDailyRepository compositeRepository;

    public RiskHeatAlertJob(RiskHeatAlertService service, HeatCompositeDailyRepository compositeRepository) {
        this.service = service;
        this.compositeRepository = compositeRepository;
    }

    @Scheduled(cron = "${ssds.risk.heat-alert.cron:0 30 6 * * *}", zone = "Asia/Taipei")
    public void run() {
        runFor(LocalDate.now(TAIPEI), Instant.now());
    }

    RiskHeatAlertService.Result runFor(LocalDate businessDate, Instant detectedAt) {
        List<Long> missing = compositeRepository.findEnabledKeywordIdsMissingStatDate(businessDate);
        if (!missing.isEmpty()) {
            log.warn("{} 仍有 {} 個啟用關鍵字沒有熱度合成資料（06:00 合成未完成或失敗），"
                    + "熱度示警以已有的資料判定。", businessDate, missing.size());
        }
        return service.detect(businessDate, detectedAt);
    }
}
