package com.example.ssds.ai.schema.calibration;

import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.ChineseNarrativeValidator;
import com.example.ssds.ai.schema.NumericTokenValidator;

import com.example.ssds.ai.model.calibration.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WeightCalibrationResponseParser {
    private static final Set<String> ROOT = Set.of("report", "adjustmentAdvice", "attentionNotes");
    private static final Set<String> ADVICE = Set.of("factorCode", "explanation");
    private static final Set<String> NARRATIVE_INTERNAL_TERMS = Set.of(
            "TREND", "MARGIN", "CVR", "PRICE_FIT", "FESTIVAL", "CLIMATE",
            "VIRAL", "REPLENISHMENT", "SEASONAL",
            "sampleSize", "regressionMethod", "factors", "factorCode", "correlation",
            "currentWeight", "suggestedWeight", "pValue", "regressionNote", "sceneOverrides",
            "totalClassifications", "overrideCount", "overrideRate", "concentratedCategories",
            "backtests", "scheme", "gradeAHitRate", "backtestNote",
            "WeightCalibrationAgent", "SceneClassifierAgent", "Prompt", "Schema",
            "規格書", "需求編號", "開發章節", "驗收條件");
    private static final Pattern DEVELOPMENT_REFERENCE = Pattern.compile("(?i)§|\\b(?:FR|AC)-?\\d");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})Q([1-4])$");
    private final ObjectMapper mapper;
    public WeightCalibrationResponseParser(ObjectMapper mapper) { this.mapper=mapper; }

    public WeightCalibrationOutput parse(String raw, WeightCalibrationInput input) {
        try (JsonParser parser=mapper.getFactory().createParser(raw)) {
            JsonNode root=mapper.readTree(parser);
            if(parser.nextToken()!=null) fail("根 JSON 後不得有額外內容");
            exact(root,ROOT,"根物件");
            String report=text(root,"report",3000);
            JsonNode adviceNodes=array(root,"adjustmentAdvice",1,6);
            Set<String> factors=new HashSet<>(); input.factors().forEach(v->factors.add(v.factorCode()));
            List<WeightCalibrationOutput.AdjustmentAdvice> advice=new ArrayList<>();
            Set<String> advised=new HashSet<>();
            for(JsonNode node:adviceNodes) {
                exact(node,ADVICE,"adjustmentAdvice[]");
                String code=text(node,"factorCode",32);
                if(!factors.contains(code)) fail("factorCode 不存在於輸入統計: "+code);
                if(!advised.add(code)) fail("factorCode 不得重複: "+code);
                advice.add(new WeightCalibrationOutput.AdjustmentAdvice(code,text(node,"explanation",500)));
            }
            JsonNode notes=array(root,"attentionNotes",1,6);
            List<String> attention=new ArrayList<>(); notes.forEach(v->attention.add(textValue(v,"attentionNotes[]",500)));
            validateNarratives(report, advice, attention, input);
            rejectInventedNumbers(report, advice, attention, input);
            return new WeightCalibrationOutput(report,List.copyOf(advice),List.copyOf(attention));
        } catch(AiSchemaValidationException e) { throw e; }
        catch(JsonProcessingException|IllegalArgumentException e) { throw new AiSchemaValidationException("WeightCalibration 回應不是有效 Schema",e); }
        catch(IOException e) { throw new AiSchemaValidationException("WeightCalibration 回應無法讀取",e); }
    }
    private static void validateNarratives(String report,List<WeightCalibrationOutput.AdjustmentAdvice> advice,
            List<String> notes,WeightCalibrationInput input) {
        Set<String> internalTerms=new HashSet<>(NARRATIVE_INTERNAL_TERMS);
        addInternalTerm(internalTerms,input.regressionMethod());
        input.factors().forEach(value->addInternalTerm(internalTerms,value.factorCode()));
        input.backtests().forEach(value->addInternalTerm(internalTerms,value.scheme()));
        requireReadableNarrative(report,"report",internalTerms);
        advice.forEach(value->requireReadableNarrative(value.explanation(),"adjustmentAdvice[].explanation",internalTerms));
        notes.forEach(value->requireReadableNarrative(value,"attentionNotes[]",internalTerms));
    }
    private static void requireReadableNarrative(String text,String field,Set<String> internalTerms) {
        ChineseNarrativeValidator.requireReadableChinese(text,field,internalTerms);
        if(DEVELOPMENT_REFERENCE.matcher(text).find()) fail(field+" 不得包含開發章節索引");
    }
    private static void addInternalTerm(Set<String> internalTerms,String value) {
        if(value!=null&&!value.isBlank()) internalTerms.add(value);
    }
    private void rejectInventedNumbers(String report,List<WeightCalibrationOutput.AdjustmentAdvice> advice,List<String> notes,WeightCalibrationInput input) {
        Set<String> allowed = allowedNumbers(input);
        StringBuilder output=new StringBuilder(report); advice.forEach(v->output.append(' ').append(v.explanation())); notes.forEach(v->output.append(' ').append(v));
        NumericTokenValidator.requireEquivalent(output.toString(), allowed, "WeightCalibration 輸出");
    }
    private static Set<String> allowedNumbers(WeightCalibrationInput input) {
        Set<String> allowed=NumericTokenValidator.equivalentTokensFrom(input.quarter(),input.regressionMethod(),input.regressionNote(),input.backtestNote());
        var quarter=QUARTER.matcher(input.quarter()==null?"":input.quarter());
        if(quarter.matches()) {
            NumericTokenValidator.addEquivalent(allowed,Long.parseLong(quarter.group(1)));
            NumericTokenValidator.addEquivalent(allowed,Long.parseLong(quarter.group(2)));
        }
        NumericTokenValidator.addEquivalent(allowed,input.sampleSize());
        input.factors().forEach(value->{
            allowed.addAll(NumericTokenValidator.equivalentTokensFrom(value.factorCode()));
            NumericTokenValidator.addEquivalent(allowed,value.correlation());
            NumericTokenValidator.addEquivalent(allowed,value.currentWeight());
            NumericTokenValidator.addEquivalent(allowed,value.suggestedWeight());
            NumericTokenValidator.addEquivalent(allowed,value.pValue());
        });
        WeightCalibrationInput.OverrideStatistics overrides=input.sceneOverrides();
        NumericTokenValidator.addEquivalent(allowed,overrides.totalClassifications());
        NumericTokenValidator.addEquivalent(allowed,overrides.overrideCount());
        NumericTokenValidator.addEquivalent(allowed,overrides.overrideRate());
        overrides.concentratedCategories().forEach(value->{
            allowed.addAll(NumericTokenValidator.equivalentTokensFrom(value.category()));
            NumericTokenValidator.addEquivalent(allowed,value.totalClassifications());
            NumericTokenValidator.addEquivalent(allowed,value.overrideCount());
            NumericTokenValidator.addEquivalent(allowed,value.overrideRate());
        });
        input.backtests().forEach(value->{
            allowed.addAll(NumericTokenValidator.equivalentTokensFrom(value.scheme()));
            NumericTokenValidator.addEquivalent(allowed,value.correlation());
            NumericTokenValidator.addEquivalent(allowed,value.gradeAHitRate());
        });
        return allowed;
    }
    private static JsonNode array(JsonNode root,String field,int min,int max) { JsonNode n=root.get(field); if(n==null||!n.isArray()||n.size()<min||n.size()>max) fail(field+" 筆數不合法"); return n; }
    private static void exact(JsonNode n,Set<String> fields,String label) { if(n==null||!n.isObject()) fail(label+" 必須是 object"); Set<String>a=new HashSet<>();n.fieldNames().forEachRemaining(a::add);if(!a.equals(fields))fail(label+" 欄位不合法"); }
    private static String text(JsonNode n,String field,int max) { return textValue(n.get(field),field,max); }
    private static String textValue(JsonNode n,String field,int max) { if(n==null||!n.isTextual())fail(field+" 必須是字串");String v=n.textValue().trim();if(v.isEmpty()||v.length()>max)fail(field+" 長度不合法");return v; }
    private static void fail(String message) { throw new AiSchemaValidationException(message); }
}
