package com.example.ssds.ai.schema.calibration;

import com.example.ssds.ai.schema.AiSchemaValidationException;

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
    @Test void rejectsSubstringAndPercentVariantsOfInputNumbers(){
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          {"report":"樣本數 2，應審慎解讀。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"照統計方向"}],"attentionNotes":["資料不足"]}
          """,WeightCalibrationAgentTest.input()));
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          {"report":"覆寫數為 20%。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"照統計方向"}],"attentionNotes":["資料不足"]}
          """,WeightCalibrationAgentTest.input()));
    }
    @Test void acceptsEquivalentDecimalFormatting(){
        assertDoesNotThrow(()->parser.parse("""
          {"report":"樣本數 200 筆，相關性為 0.200。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"依統計方向調整。"}],"attentionNotes":["覆寫率為 0.20，仍需審慎解讀。"]}
          """,WeightCalibrationAgentTest.input()));
    }
    @Test void acceptsQuarterRenderedAsNaturalChinese(){
        assertDoesNotThrow(()->parser.parse("""
          {"report":"2026 年第 3 季的樣本數為 200 筆。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"依統計方向調整。"}],"attentionNotes":["資料仍需審慎解讀。"]}
          """,WeightCalibrationAgentTest.input()));
    }
    @Test void rejectsInternalFactorCodesAndDevelopmentReferencesInUserFacingText(){
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          {"report":"PRICE_FIT 的表現值得留意。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"依統計方向調整。"}],"attentionNotes":["資料不足，應審慎解讀。"]}
          """,WeightCalibrationAgentTest.input()));
        assertThrows(AiSchemaValidationException.class,()->parser.parse("""
          {"report":"價格帶適配度的表現值得留意。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"依 §5.2.1 的規則調整。"}],"attentionNotes":["資料不足，應審慎解讀。"]}
          """,WeightCalibrationAgentTest.input()));
    }
    @Test void acceptsFluentTraditionalChineseNarrativesWithStructuredFactorCode(){
        assertDoesNotThrow(()->parser.parse("""
          {"report":"樣本數 200 筆，價格帶適配度的統計訊號值得持續觀察。","adjustmentAdvice":[{"factorCode":"TREND","explanation":"社群熱度趨勢可依統計模組的建議方向調整，不另提出數值。"}],"attentionNotes":["零食品類覆寫率為 0.20，需確認情境判定是否穩定。"]}
          """,WeightCalibrationAgentTest.input()));
    }
}
