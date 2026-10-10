package com.example.ssds.api.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.ssds.api.risk.RiskFestivalAlertService;
import com.example.ssds.api.risk.RiskHeatAlertService;
import com.example.ssds.api.risk.RiskSeasonAlertService;

/**
 * 應用啟動完成後，把每日風險偵測（熱度異常、季節不匹配、節慶檔期）各補跑一次，
 * 不必等到 06:30／06:35 的排程。不呼叫 LLM。
 *
 * <p>另開執行緒跑，不拖慢啟動；任何一項失敗都只記錄，不影響其他項目與應用本身。
 *
 * <p>重複執行是安全的：示警寫入有（品項, 類型）鎖與 7 日去重，再跑只會更新同一筆。
 * 多節點同時啟動只會白跑，不會多開示警。
 *
 * <p>熱度偵測只看「今天」的合成資料，不退回昨天（見 {@link RiskHeatAlertService}）。
 * 若啟動時間早於當天 06:00 的合成，熱度這一項會因當天沒有資料而略過並記警告，
 * 季節與節慶兩項不受影響。
 *
 * <p>是否執行由 S-14「補跑與兜底」設定控制，properties／環境變數只提供初始預設值。
 */
@Component
public class RiskAlertStartupRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertStartupRunner.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final RiskHeatAlertService heatService;
    private final RiskSeasonAlertService seasonService;
    private final RiskFestivalAlertService festivalService;
    private final Executor executor;

    @Autowired
    public RiskAlertStartupRunner(
            RiskHeatAlertService heatService,
            RiskSeasonAlertService seasonService,
            RiskFestivalAlertService festivalService) {
        this(heatService, seasonService, festivalService, task -> {
            Thread thread = new Thread(task, "risk-startup-run");
            thread.setDaemon(true);
            thread.start();
        });
    }

    RiskAlertStartupRunner(
            RiskHeatAlertService heatService,
            RiskSeasonAlertService seasonService,
            RiskFestivalAlertService festivalService,
            Executor executor) {
        this.heatService = heatService;
        this.seasonService = seasonService;
        this.festivalService = festivalService;
        this.executor = executor;
    }

    public void onReady() {
        executor.execute(() -> runOnce(Instant.now()));
    }

    void runOnce(Instant detectedAt) {
        LocalDate today = LocalDate.now(TAIPEI);
        log.info("應用啟動，補跑一次風險偵測：businessDate={}", today);
        try {
            heatService.detect(today, detectedAt);
        } catch (RuntimeException exception) {
            log.error("啟動補跑：熱度異常示警偵測失敗", exception);
        }
        try {
            seasonService.detect(detectedAt);
        } catch (RuntimeException exception) {
            log.error("啟動補跑：季節不匹配示警偵測失敗", exception);
        }
        try {
            festivalService.detect(today, detectedAt);
        } catch (RuntimeException exception) {
            log.error("啟動補跑：節慶檔期示警偵測失敗", exception);
        }
    }
}
