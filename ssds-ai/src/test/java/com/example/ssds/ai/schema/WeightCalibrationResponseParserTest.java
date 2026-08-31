package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.example.ssds.ai.agent.WeightCalibrationAgentTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WeightCalibrationResponseParserTest {
    private final WeightCalibrationResponseParser parser=new WeightCalibrationResponseParser(new ObjectMapper());
    @Test void rejectsUnknownFactorAndInventedNumber(){
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          {"report":"摘要","adjustmentAdvice":[{"factorCode":"MARGIN","explanation":"照統計方向"}],"attentionNotes":["資料不足"]}
          """,WeightCalibrationAgentTest.input()));
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          {"report":"建議權重 0.99","adjustmentAdvice":[{"factorCode":"TREND","explanation":"照統計方向"}],"attentionNotes":["資料不足"]}
          """,WeightCalibrationAgentTest.input()));
    }
    @Test void rejectsMarkdownWrapper(){
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          ```json
          {"report":"摘要","adjustmentAdvice":[{"factorCode":"TREND","explanation":"照統計方向"}],"attentionNotes":["資料不足"]}
          ```
          """,WeightCalibrationAgentTest.input()));
    }
}
