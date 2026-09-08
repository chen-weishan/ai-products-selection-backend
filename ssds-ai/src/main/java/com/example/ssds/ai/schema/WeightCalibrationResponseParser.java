package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class WeightCalibrationResponseParser {
    private static final Set<String> ROOT = Set.of("report", "adjustmentAdvice", "attentionNotes");
    private static final Set<String> ADVICE = Set.of("factorCode", "explanation");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}_])-?\\d+(?:\\.\\d+)?%?");
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
            rejectInventedNumbers(report, advice, attention, input);
            return new WeightCalibrationOutput(report,List.copyOf(advice),List.copyOf(attention));
        } catch(AiSchemaValidationException e) { throw e; }
        catch(JsonProcessingException|IllegalArgumentException e) { throw new AiSchemaValidationException("WeightCalibration 回應不是有效 Schema",e); }
        catch(IOException e) { throw new AiSchemaValidationException("WeightCalibration 回應無法讀取",e); }
    }
    private void rejectInventedNumbers(String report,List<WeightCalibrationOutput.AdjustmentAdvice> advice,List<String> notes,WeightCalibrationInput input) {
        String source;
        try { source=mapper.writeValueAsString(input); } catch(JsonProcessingException e) { throw new AiSchemaValidationException("無法驗證輸出數值",e); }
        StringBuilder output=new StringBuilder(report); advice.forEach(v->output.append(' ').append(v.explanation())); notes.forEach(v->output.append(' ').append(v));
        Matcher matcher=NUMBER.matcher(output);
        while(matcher.find()) {
            String token=matcher.group().replace("%","");
            if(!source.contains(token)) fail("輸出包含輸入不存在的數字: "+matcher.group());
        }
    }
    private static JsonNode array(JsonNode root,String field,int min,int max) { JsonNode n=root.get(field); if(n==null||!n.isArray()||n.size()<min||n.size()>max) fail(field+" 筆數不合法"); return n; }
    private static void exact(JsonNode n,Set<String> fields,String label) { if(n==null||!n.isObject()) fail(label+" 必須是 object"); Set<String>a=new HashSet<>();n.fieldNames().forEachRemaining(a::add);if(!a.equals(fields))fail(label+" 欄位不合法"); }
    private static String text(JsonNode n,String field,int max) { return textValue(n.get(field),field,max); }
    private static String textValue(JsonNode n,String field,int max) { if(n==null||!n.isTextual())fail(field+" 必須是字串");String v=n.textValue().trim();if(v.isEmpty()||v.length()>max)fail(field+" 長度不合法");return v; }
    private static void fail(String message) { throw new AiSchemaValidationException(message); }
}
