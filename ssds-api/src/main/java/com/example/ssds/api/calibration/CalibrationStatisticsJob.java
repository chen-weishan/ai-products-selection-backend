package com.example.ssds.api.calibration;

import com.example.ssds.api.common.error.BusinessException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 季度校準的唯一排程入口（§FR-15 步驟 1→2；§5.10 排程表、附錄 B {@code SCHEDULE_CALIBRATION}）：
 * 產生上一季統計報告後，直接建立 Agent 7 解讀任務。
 *
 * <p>§5.10：每季首日 08:00，避開週一 07:00 的全量評分——首日為週一時順延至 09:00。
 * 預設 cron 在 08、09 時各觸發一次，由本 job 依星期挑一次執行；自訂 cron 的其他時段不受此限。
 * 附錄 B 的預設值只有 8 點，照抄會讓首日為週一的季度漏產報告，啟動時檢查並記 warn。
 *
 * <p>產生報告與建立 AI 任務在同一個 job 內依序進行：兩支 job 各自排在季初時，
 * 解讀那支可能先跑而找不到報告，或解讀到隨後被重算覆蓋的舊數字。
 * {@code ai.calibration.schedule-enabled=false} 只關閉自動解讀，報告照常產生。
 *
 * <p>多台開發機連同一個資料庫時會同時觸發：先寫入者成功，其餘拿到 409（BusinessException）並略過，
 * 不會重複建立 AI 任務。
 */
@Component
public class CalibrationStatisticsJob {

    private static final Logger log = LoggerFactory.getLogger(CalibrationStatisticsJob.class);
    static final String DEFAULT_CRON = "0 0 8,9 1 1,4,7,10 *";

    private final CalibrationReportService reportService;
    private final CalibrationInterpretations interpretations;
    private final boolean interpretEnabled;
    private final Clock clock;

    @Autowired
    public CalibrationStatisticsJob(
            CalibrationReportService reportService,
            CalibrationInterpretations interpretations,
            @Value("${ai.calibration.schedule-enabled:true}") boolean interpretEnabled,
            @Value("${ai.calibration.schedule-cron:" + DEFAULT_CRON + "}") String cron) {
        this(reportService, interpretations, interpretEnabled, Clock.system(CalibrationQuarter.ZONE));
        if (!coversMondayNineOClock(cron)) {
            log.warn("SCHEDULE_CALIBRATION={} 不含 09:00：季度首日為週一時 08:00 會依 §5.10 跳過，"
                    + "該季將不會產生校準報告。請改為 {}", cron, DEFAULT_CRON);
        }
    }

    CalibrationStatisticsJob(
            CalibrationReportService reportService,
            CalibrationInterpretations interpretations,
            boolean interpretEnabled,
            Clock clock) {
        this.reportService = reportService;
        this.interpretations = interpretations;
        this.interpretEnabled = interpretEnabled;
        this.clock = clock;
    }

    /**
     * 以 2029-01-01（季度首日且為週一）驗證 cron 在 09:00 會觸發。
     * {@code -}（Spring 的停用排程）不檢查。
     */
    static boolean coversMondayNineOClock(String cron) {
        if (Scheduled.CRON_DISABLED.equals(cron.trim())) {
            return true;
        }
        LocalDateTime nineOClock = LocalDateTime.of(2029, 1, 1, 9, 0);
        return nineOClock.equals(CronExpression.parse(cron).next(nineOClock.minusSeconds(1)));
    }

    public void run() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(CalibrationQuarter.ZONE);
        int expectedHour = now.getDayOfWeek() == DayOfWeek.MONDAY ? 9 : 8;
        if ((now.getHour() == 8 || now.getHour() == 9) && now.getHour() != expectedHour) {
            return;
        }
        String quarter = CalibrationQuarter.of(now.toLocalDate()).previous().toString();
        Long reportId;
        try {
            var report = reportService.generate(quarter);
            reportId = report.id();
            log.info("Calibration report generated: quarter={}, id={}, sampleSize={}",
                    quarter, report.id(), report.sampleSize());
        } catch (BusinessException e) {
            // 已審核、無生效版本、他台已產生等屬預期狀況，記錄後略過，不讓排程執行緒拋例外
            log.warn("Calibration report skipped: quarter={}, reason={}", quarter, e.getMessage());
            return;
        }
        if (interpretEnabled) {
            interpretations.request(reportId, quarter);
        }
    }
}
