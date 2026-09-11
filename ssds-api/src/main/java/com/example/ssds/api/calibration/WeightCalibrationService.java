package com.example.ssds.api.calibration;

import com.example.ssds.ai.agent.WeightCalibrationAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.calibration.dto.*;
import com.example.ssds.api.common.error.*;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeightCalibrationService {
    private static final Logger log=LoggerFactory.getLogger(WeightCalibrationService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private final CalibrationReportRepository reports; private final PromptSanitizer sanitizer;
    private final SceneClassificationLogRepository scenes;
    private final WeightCalibrationAgent agent; private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    public WeightCalibrationService(
            CalibrationReportRepository reports,
            SceneClassificationLogRepository scenes,
            PromptSanitizer sanitizer,
            WeightCalibrationAgent agent,
            ObjectMapper mapper) {
        this.reports=reports;this.scenes=scenes;this.sanitizer=sanitizer;this.agent=agent;this.mapper=mapper;
    }
    public WeightCalibrationService(CalibrationReportRepository reports,PromptSanitizer sanitizer,
            WeightCalibrationAgent agent,ObjectMapper mapper){this.reports=reports;this.scenes=null;this.sanitizer=sanitizer;this.agent=agent;this.mapper=mapper;}

    /** AI task entry point: derives override statistics from the report quarter. */
    @Transactional
    public WeightCalibrationResponse interpret(Long reportId, boolean forceRefresh) {
        CalibrationReport report = load(reportId);
        if (scenes == null) throw new IllegalStateException("SceneClassificationLogRepository 尚未設定");
        LocalDate start = quarterStart(report.getQuarter());
        Instant from = start.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant to = start.plusMonths(3).atStartOfDay(BUSINESS_ZONE).toInstant();
        return interpret(
                reportId,
                new WeightCalibrationInterpretRequest(
                        statistics(scenes.findByCreatedAtBetween(from, to)), forceRefresh));
    }

    @Transactional
    public WeightCalibrationResponse interpret(Long reportId,WeightCalibrationInterpretRequest request){
        CalibrationReport report=load(reportId);
        validateOverrides(request.sceneOverrides());
        JsonNode regression=read(report.getRegressionResult(),"regression_result");
        JsonNode backtest=read(report.getBacktestResult(),"backtest_result");
        WeightCalibrationInput input=sanitizer.sanitizeWeightCalibration(buildInput(report,regression,backtest,request.sceneOverrides()));
        WeightCalibrationResult result=agent.interpret(input,request.forceRefresh());
        Instant now=Instant.now();
        report.setAiInterpretation(result.output().report());
        report.setAdjustmentAdvice(write(result.output().adjustmentAdvice()));
        report.setAttentionNotes(write(result.output().attentionNotes()));
        report.setModel(result.fallbackApplied()?"rule-fallback":result.model());
        report.setPromptVersion(result.promptVersion()); report.setInterpretedAt(now); reports.save(report);
        log.info("WeightCalibration completed: reportId={}, quarter={}, promptVersion={}, modelAlias=MODEL_REASONING, fallback={}, cacheHit={}",
                reportId,report.getQuarter(),result.promptVersion(),result.fallbackApplied(),result.cacheHit());
        return WeightCalibrationResponse.from(report,regression,backtest,result,now);
    }
    private WeightCalibrationInput buildInput(CalibrationReport report,JsonNode regression,JsonNode backtest,
            WeightCalibrationInterpretRequest.SceneOverrideStatistics overrides){
        List<WeightCalibrationInput.FactorStatistic> factors=new ArrayList<>();
        JsonNode factorNodes=regression.path("factors");
        if(factorNodes.isArray())factorNodes.forEach(node->factors.add(new WeightCalibrationInput.FactorStatistic(
                requiredText(node,"code"),decimal(node,"correlation"),decimal(node,"currentWeight"),
                decimal(node,"suggestedWeight"),decimal(node,"pValue"))));
        List<WeightCalibrationInput.BacktestStatistic> backtests=new ArrayList<>();
        JsonNode backtestNodes=backtest.path("backtests");
        if(backtestNodes.isArray())backtestNodes.forEach(node->backtests.add(new WeightCalibrationInput.BacktestStatistic(
                requiredText(node,"scheme"),decimal(node,"correlation"),decimal(node,"gradeAHitRate"))));
        var categories=overrides.concentratedCategories().stream().map(value->
                new WeightCalibrationInput.CategoryOverrideStatistic(value.category(),value.totalClassifications(),
                        value.overrideCount(),value.overrideRate())).toList();
        return new WeightCalibrationInput(report.getQuarter(),report.getSampleSize(),regression.path("method").asText("unspecified"),
                List.copyOf(factors),nullableText(regression,"note"),
                new WeightCalibrationInput.OverrideStatistics(overrides.totalClassifications(),overrides.overrideCount(),overrides.overrideRate(),categories),
                List.copyOf(backtests),nullableText(backtest,"note"));
    }
    private static void validateOverrides(WeightCalibrationInterpretRequest.SceneOverrideStatistics value){
        if(value.overrideCount()>value.totalClassifications())throw new BusinessException(ErrorCode.VALIDATION_FAILED,"覆寫數不可大於總判定數");
        for(var item:value.concentratedCategories())if(item.overrideCount()>item.totalClassifications())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"品類覆寫數不可大於該品類總判定數");
    }
    private static LocalDate quarterStart(String quarter) {
        if (quarter == null || !quarter.matches("\\d{4}Q[1-4]")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "校準報告 quarter 格式必須為 YYYYQ1～YYYYQ4");
        }
        int year = Integer.parseInt(quarter.substring(0, 4));
        int quarterNumber = quarter.charAt(5) - '0';
        return LocalDate.of(year, (quarterNumber - 1) * 3 + 1, 1);
    }
    private static WeightCalibrationInterpretRequest.SceneOverrideStatistics statistics(
            List<SceneClassificationLog> values) {
        int total = values.size();
        int overridden = (int) values.stream().filter(SceneClassificationLog::isOverridden).count();
        Map<String, List<SceneClassificationLog>> groups = values.stream().collect(
                Collectors.groupingBy(value -> value.getProduct().getCategory().getName()));
        List<WeightCalibrationInterpretRequest.CategoryOverrideStatistic> categories = groups.entrySet().stream()
                .map(entry -> {
                    int count = entry.getValue().size();
                    int overrides = (int) entry.getValue().stream()
                            .filter(SceneClassificationLog::isOverridden).count();
                    return new WeightCalibrationInterpretRequest.CategoryOverrideStatistic(
                            entry.getKey(), count, overrides, rate(overrides, count));
                })
                .filter(value -> value.overrideCount() > 0)
                .sorted(Comparator.comparing(
                        WeightCalibrationInterpretRequest.CategoryOverrideStatistic::overrideRate).reversed())
                .limit(5)
                .toList();
        return new WeightCalibrationInterpretRequest.SceneOverrideStatistics(
                total, overridden, rate(overridden, total), categories);
    }
    private static BigDecimal rate(int numerator, int denominator) {
        return denominator == 0
                ? BigDecimal.ZERO
                : new BigDecimal(numerator).divide(
                        new BigDecimal(denominator), 4, java.math.RoundingMode.HALF_UP);
    }
    private CalibrationReport load(Long id){return reports.findById(id).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"找不到指定的校準報告"));}
    private JsonNode read(String raw,String field){if(raw==null||raw.isBlank())return mapper.createObjectNode();try{return mapper.readTree(raw);}catch(JsonProcessingException e){throw new BusinessException(ErrorCode.VALIDATION_FAILED,field+" 不是有效 JSON");}}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("無法序列化校準解讀",e);}}
    private static String requiredText(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isTextual()||v.asText().isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED,"校準統計缺少 "+f);return v.asText();}
    private static BigDecimal decimal(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isNumber())throw new BusinessException(ErrorCode.VALIDATION_FAILED,"校準統計缺少數值 "+f);return v.decimalValue();}
    private static String nullableText(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.isTextual()?v.asText():null;}
}
