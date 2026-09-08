package com.example.ssds.api.calibration;

import com.example.ssds.ai.agent.WeightCalibrationAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.calibration.dto.*;
import com.example.ssds.api.common.error.*;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeightCalibrationService {
    private static final Logger log=LoggerFactory.getLogger(WeightCalibrationService.class);
    private final CalibrationReportRepository reports; private final PromptSanitizer sanitizer;
    private final WeightCalibrationAgent agent; private final ObjectMapper mapper;
    public WeightCalibrationService(CalibrationReportRepository reports,PromptSanitizer sanitizer,
            WeightCalibrationAgent agent,ObjectMapper mapper){this.reports=reports;this.sanitizer=sanitizer;this.agent=agent;this.mapper=mapper;}

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
    private CalibrationReport load(Long id){return reports.findById(id).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"找不到指定的校準報告"));}
    private JsonNode read(String raw,String field){if(raw==null||raw.isBlank())return mapper.createObjectNode();try{return mapper.readTree(raw);}catch(JsonProcessingException e){throw new BusinessException(ErrorCode.VALIDATION_FAILED,field+" 不是有效 JSON");}}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("無法序列化校準解讀",e);}}
    private static String requiredText(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isTextual()||v.asText().isBlank())throw new BusinessException(ErrorCode.VALIDATION_FAILED,"校準統計缺少 "+f);return v.asText();}
    private static BigDecimal decimal(JsonNode n,String f){JsonNode v=n.get(f);if(v==null||!v.isNumber())throw new BusinessException(ErrorCode.VALIDATION_FAILED,"校準統計缺少數值 "+f);return v.decimalValue();}
    private static String nullableText(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.isTextual()?v.asText():null;}
}
