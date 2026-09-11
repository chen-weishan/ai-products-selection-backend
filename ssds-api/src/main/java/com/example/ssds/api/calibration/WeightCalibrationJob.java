package com.example.ssds.api.calibration;

import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.infra.repository.*;
import java.time.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每季統計報告產生後執行 AI 解讀；測試階段預設停用，仍可由 Postman 觸發。 */
@Component
@ConditionalOnProperty(name="ai.calibration.schedule-enabled",havingValue="true")
public class WeightCalibrationJob {
    private static final Logger log=LoggerFactory.getLogger(WeightCalibrationJob.class);
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final CalibrationReportRepository reports;
    private final AiTaskService taskService;
    private final Clock clock;
    @Autowired
    public WeightCalibrationJob(CalibrationReportRepository reports,AiTaskService taskService){
        this(reports,taskService,Clock.system(ZONE));}
    WeightCalibrationJob(CalibrationReportRepository reports,AiTaskService taskService,Clock clock){
        this.reports=reports;this.taskService=taskService;this.clock=clock;}

    @Scheduled(cron="${ai.calibration.schedule-cron:0 0 8,9 1 1,4,7,10 *}",zone="Asia/Taipei")
    public void run(){
        ZonedDateTime now=ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        int expectedHour=now.getDayOfWeek()==DayOfWeek.MONDAY?9:8;
        if(now.getHour()!=expectedHour)return;
        LocalDate currentQuarterStart=now.toLocalDate().withMonth(((now.getMonthValue()-1)/3)*3+1).withDayOfMonth(1);
        LocalDate start=currentQuarterStart.minusMonths(3);
        int quarter=(start.getMonthValue()-1)/3+1;
        String quarterCode=start.getYear()+"Q"+quarter;
        reports.findByQuarter(quarterCode).ifPresentOrElse(report->{
            taskService.create(new com.example.ssds.api.aitask.dto.CreateAiTaskRequest(
                    com.example.ssds.core.domain.AiTaskType.WEIGHT_CALIBRATION,
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(report.getId()),
                    new com.example.ssds.api.aitask.dto.CreateAiTaskRequest.Options(false)));
        },()->log.info("WeightCalibration schedule skipped: quarter={} statistical report not ready",quarterCode));
    }
}
