package com.example.ssds.api.calibration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.example.ssds.ai.agent.WeightCalibrationAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.calibration.dto.*;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WeightCalibrationServiceTest {
    @Test void sendsOnlyAggregatesAndPersistsStructuredInterpretation(){
        CalibrationReportRepository repository=mock(CalibrationReportRepository.class);
        WeightCalibrationAgent agent=mock(WeightCalibrationAgent.class);
        CalibrationReport report=CalibrationReport.builder().id(7L).quarter("2026Q3").sampleSize(200)
                .regressionResult("{\"method\":\"pearson\",\"factors\":[{\"code\":\"TREND\",\"correlation\":0.71,\"currentWeight\":0.50,\"suggestedWeight\":0.47,\"pValue\":0.11}]}")
                .backtestResult("{\"backtests\":[{\"scheme\":\"CURRENT\",\"correlation\":0.63,\"gradeAHitRate\":0.67}]}").build();
        when(repository.findById(7L)).thenReturn(Optional.of(report));
        when(agent.interpret(any(),eq(false))).thenReturn(new WeightCalibrationResult(
                new WeightCalibrationOutput("統計解讀",List.of(new WeightCalibrationOutput.AdjustmentAdvice("TREND","依建議方向調整")),List.of("注意樣本代表性")),
                false,null,false,"test-model","calibration-v1",10,5,1));
        WeightCalibrationService service=new WeightCalibrationService(repository,new PromptSanitizer(),agent,new ObjectMapper());
        var request=new WeightCalibrationInterpretRequest(new WeightCalibrationInterpretRequest.SceneOverrideStatistics(
                200,20,new BigDecimal("0.10"),List.of(new WeightCalibrationInterpretRequest.CategoryOverrideStatistic("零食",50,10,new BigDecimal("0.20")))),false);

        var response=service.interpret(7L,request);

        ArgumentCaptor<WeightCalibrationInput> input=ArgumentCaptor.forClass(WeightCalibrationInput.class);
        verify(agent).interpret(input.capture(),eq(false));
        assertEquals(1,input.getValue().factors().size());
        assertEquals(200,input.getValue().sceneOverrides().totalClassifications());
        assertEquals("統計解讀",report.getAiInterpretation());
        assertTrue(report.getAdjustmentAdvice().contains("TREND"));
        assertEquals("calibration-v1",report.getPromptVersion());
        assertFalse(response.fallbackApplied());
    }
}
