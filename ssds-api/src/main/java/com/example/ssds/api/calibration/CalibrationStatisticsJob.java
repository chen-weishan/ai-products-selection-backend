package com.example.ssds.api.calibration;

import com.example.ssds.api.common.error.BusinessException;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每季產生上一季的校準統計報告（§FR-15 步驟 1；附錄 B {@code SCHEDULE_CALIBRATION}，預設每季首日 08:00）。
 *
 * <p>{@code WeightCalibrationJob}（Agent 7 解讀）也在季初 08:00 前後找同一季報告，
 * 兩者同時觸發時可能讀不到本 job 剛寫入的報告；與 Agent 7 整合時應改為本 job 產生後直接建立 AI 任務。
 *
 * <p>多台開發機連同一個資料庫時會同時觸發：先寫入者成功，其餘拿到 409（BusinessException）並略過。
 */
@Component
public class CalibrationStatisticsJob {

    private static final Logger log = LoggerFactory.getLogger(CalibrationStatisticsJob.class);

    private final CalibrationReportService reportService;

    public CalibrationStatisticsJob(CalibrationReportService reportService) {
        this.reportService = reportService;
    }

    @Scheduled(cron = "${SCHEDULE_CALIBRATION:0 0 8 1 1,4,7,10 *}", zone = "Asia/Taipei")
    public void run() {
        String quarter = CalibrationQuarter.of(LocalDate.now(CalibrationQuarter.ZONE)).previous().toString();
        try {
            var report = reportService.generate(quarter);
            log.info("Calibration report generated: quarter={}, id={}, sampleSize={}",
                    quarter, report.id(), report.sampleSize());
        } catch (BusinessException e) {
            // 已審核、無生效版本等屬預期狀況，記錄後略過，不讓排程執行緒拋例外
            log.warn("Calibration report skipped: quarter={}, reason={}", quarter, e.getMessage());
        }
    }
}
