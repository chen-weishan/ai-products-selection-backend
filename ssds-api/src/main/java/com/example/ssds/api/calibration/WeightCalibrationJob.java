package com.example.ssds.api.calibration;

import com.example.ssds.api.calibration.dto.WeightCalibrationInterpretRequest;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
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
    private final CalibrationReportRepository reports; private final SceneClassificationLogRepository scenes;
    private final WeightCalibrationService service;
    private final Clock clock;
    @Autowired
    public WeightCalibrationJob(CalibrationReportRepository reports,SceneClassificationLogRepository scenes,WeightCalibrationService service){
        this(reports,scenes,service,Clock.system(ZONE));}
    WeightCalibrationJob(CalibrationReportRepository reports,SceneClassificationLogRepository scenes,WeightCalibrationService service,Clock clock){
        this.reports=reports;this.scenes=scenes;this.service=service;this.clock=clock;}

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
            Instant from=start.atStartOfDay(ZONE).toInstant();Instant to=start.plusMonths(3).atStartOfDay(ZONE).toInstant();
            List<SceneClassificationLog> values=scenes.findByCreatedAtBetween(from,to);
            service.interpret(report.getId(),new WeightCalibrationInterpretRequest(statistics(values),false));
        },()->log.info("WeightCalibration schedule skipped: quarter={} statistical report not ready",quarterCode));
    }
    private static WeightCalibrationInterpretRequest.SceneOverrideStatistics statistics(List<SceneClassificationLog> values){
        int total=values.size();int overridden=(int)values.stream().filter(SceneClassificationLog::isOverridden).count();
        Map<String,List<SceneClassificationLog>> groups=values.stream().collect(Collectors.groupingBy(v->v.getProduct().getCategory().getName()));
        List<WeightCalibrationInterpretRequest.CategoryOverrideStatistic> categories=groups.entrySet().stream().map(entry->{
            int count=entry.getValue().size();int overrides=(int)entry.getValue().stream().filter(SceneClassificationLog::isOverridden).count();
            return new WeightCalibrationInterpretRequest.CategoryOverrideStatistic(entry.getKey(),count,overrides,rate(overrides,count));
        }).filter(value->value.overrideCount()>0).sorted(Comparator.comparing(WeightCalibrationInterpretRequest.CategoryOverrideStatistic::overrideRate).reversed()).limit(5).toList();
        return new WeightCalibrationInterpretRequest.SceneOverrideStatistics(total,overridden,rate(overridden,total),categories);
    }
    private static BigDecimal rate(int numerator,int denominator){return denominator==0?BigDecimal.ZERO:new BigDecimal(numerator).divide(new BigDecimal(denominator),4,RoundingMode.HALF_UP);}
}
