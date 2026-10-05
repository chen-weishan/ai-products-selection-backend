package com.example.ssds.api.calibration;

import com.example.ssds.api.aitask.dto.CreateAiTaskRequest;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.core.domain.AiTaskType;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 季度校準的唯一排程入口（§FR-15 步驟 1→2；§5.10 排程表、附錄 B {@code SCHEDULE_CALIBRATION}）：
 * 產生上一季統計報告後，直接建立 Agent 7 解讀任務。
 *
 * <p>§5.10：每季首日 08:00，避開週一 07:00 的全量評分——首日為週一時順延至 09:00。
 * 預設 cron 在 08、09 時各觸發一次，由本 job 依星期挑一次執行；自訂 cron 的其他時段不受此限。
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

    private final CalibrationReportService reportService;
    private final AiTaskService aiTaskService;
    private final boolean interpretEnabled;
    private final Clock clock;

    @Autowired
    public CalibrationStatisticsJob(
            CalibrationReportService reportService,
            AiTaskService aiTaskService,
            @Value("${ai.calibration.schedule-enabled:true}") boolean interpretEnabled) {
        this(reportService, aiTaskService, interpretEnabled, Clock.system(CalibrationQuarter.ZONE));
    }

    CalibrationStatisticsJob(
            CalibrationReportService reportService,
            AiTaskService aiTaskService,
            boolean interpretEnabled,
            Clock clock) {
        this.reportService = reportService;
        this.aiTaskService = aiTaskService;
        this.interpretEnabled = interpretEnabled;
        this.clock = clock;
    }

    @Scheduled(cron = "${ai.calibration.schedule-cron:0 0 8,9 1 1,4,7,10 *}", zone = "Asia/Taipei")
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
        if (!interpretEnabled) {
            return;
        }
        try {
            var task = aiTaskService.create(new CreateAiTaskRequest(
                    AiTaskType.WEIGHT_CALIBRATION, List.of(), List.of(), List.of(reportId),
                    new CreateAiTaskRequest.Options(false)));
            log.info("Calibration interpretation task created: quarter={}, reportId={}, taskId={}",
                    quarter, reportId, task.taskId());
        } catch (RuntimeException e) {
            // 報告已落盤；解讀可經 POST /calibration/reports/{id}/interpretation 補觸發（S-19 目前沒有按鈕）
            log.warn("Calibration interpretation task not created: quarter={}, reportId={}, reason={}",
                    quarter, reportId, e.getMessage());
        }
    }
}
