package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.example.ssds.ai.access.common.AiModelNotFoundException;
import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.AiPromptRequest;
import com.example.ssds.ai.access.tracka.TrackAAiClient;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.calibration.*;
import com.example.ssds.ai.prompt.calibration.WeightCalibrationPromptFactory;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.ai.resilience.RetrySleeper;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.calibration.WeightCalibrationResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

public class WeightCalibrationAgentTest {
    @Test void retryUsesSameModelThenFallbackAndReturnsValidOutput(){
        FakeClient client=new FakeClient("{\"invalid\":true}","{\"invalid\":true}",validJson());
        WeightCalibrationResult result=agent(client).interpret(input(),false);
        assertFalse(result.fallbackApplied()); assertEquals(3,result.requestCount());
        assertEquals(List.of("fake/primary","fake/primary","fake/fallback"),client.models);
        assertEquals(List.of(false,true,true),client.retryAttempts);
    }
    @Test void repeatedFailureFallsBackToRawStatisticsMessage(){
        FakeClient client=new FakeClient(new IllegalStateException("offline"));
        WeightCalibrationResult result=agent(client).interpret(input(),false);
        assertTrue(result.fallbackApplied());
        assertEquals("AI 解讀未完成，請直接查看統計原始結果表。",result.output().report());
        assertTrue(result.output().adjustmentAdvice().isEmpty());
        assertEquals(List.of("fake/primary","fake/fallback","fake/third"),client.models);
    }
    @Test void successfulResultIsNeverCached(){
        FakeClient client=new FakeClient(validJson()); WeightCalibrationAgent agent=agent(client);
        assertFalse(agent.interpret(input(),false).cacheHit()); assertFalse(agent.interpret(input(),false).cacheHit());
        assertEquals(2,client.calls.get());
    }

    @Test void budgetExceptionIsPropagatedForTaskDeferral(){
        AiBudgetExceededException budgetExceeded = new AiBudgetExceededException(
                com.example.ssds.core.domain.AiTaskType.BudgetPool.RETRY,
                OffsetDateTime.parse("2026-09-15T00:00:00+08:00"));
        FakeClient client=new FakeClient(budgetExceeded);

        AiBudgetExceededException thrown=assertThrows(
                AiBudgetExceededException.class,
                ()->agent(client).interpret(input(),false));

        assertSame(budgetExceeded,thrown);
        assertEquals(1,client.calls.get());
    }

    @Test void rateLimitUsesOneTwoFourSecondBackoffOnPrimaryModel(){
        FakeClient client=new FakeClient(
                new AiRateLimitException("limited",null),
                new AiRateLimitException("limited",null),
                new AiRateLimitException("limited",null),
                validJson());
        List<Long> delays=new ArrayList<>();

        WeightCalibrationResult result=agent(client,delays::add).interpret(input(),false);

        assertFalse(result.fallbackApplied());
        assertEquals(4,result.requestCount());
        assertEquals(List.of(1_000L,2_000L,4_000L),delays);
        assertEquals(List.of("fake/primary","fake/primary","fake/primary","fake/primary"),client.models);
    }

    @Test void timeoutAndModelNotFoundSwitchModelsWithoutBackoff(){
        FakeClient client=new FakeClient(
                new ResourceAccessException("timeout"),
                new AiModelNotFoundException("fake/fallback",null),
                validJson());
        List<Long> delays=new ArrayList<>();

        WeightCalibrationResult result=agent(client,delays::add).interpret(input(),false);

        assertFalse(result.fallbackApplied());
        assertEquals(List.of("fake/primary","fake/fallback","fake/third"),client.models);
        assertTrue(delays.isEmpty());
    }

    private static WeightCalibrationAgent agent(TrackAAiClient client){return agent(client,ms->{});}
    private static WeightCalibrationAgent agent(TrackAAiClient client,RetrySleeper sleeper){ObjectMapper m=new ObjectMapper();return new WeightCalibrationAgent(
            new AiAccessRouter(client),new WeightCalibrationPromptFactory(m),new WeightCalibrationResponseParser(m),m,
            "fake/primary","fake/fallback,fake/third",3,sleeper);}
    public static WeightCalibrationInput input(){return new WeightCalibrationInput("2026Q3",200,"pearson",
            List.of(new WeightCalibrationInput.FactorStatistic("TREND",bd("0.71"),bd("0.50"),bd("0.47"),bd("0.11"))),
            "結果僅供參考",new WeightCalibrationInput.OverrideStatistics(200,20,bd("0.10"),
            List.of(new WeightCalibrationInput.CategoryOverrideStatistic("零食",50,10,bd("0.20")))),
            List.of(new WeightCalibrationInput.BacktestStatistic("CURRENT",bd("0.63"),bd("0.67"))),"回測摘要");}
    private static BigDecimal bd(String v){return new BigDecimal(v);}
    private static String validJson(){return """
            {"report":"樣本數 200，結果應配合回測審慎解讀。",
             "adjustmentAdvice":[{"factorCode":"TREND","explanation":"依統計模組建議方向調整，不另提出數值。"}],
             "attentionNotes":["零食品類覆寫率為 0.20，需確認是否為情境判定問題。"]}
            """;}
    private static final class FakeClient implements TrackAAiClient {final List<Object> outcomes;final AtomicInteger calls=new AtomicInteger();final List<String>models=new ArrayList<>();final List<Boolean>retryAttempts=new ArrayList<>();
        FakeClient(Object...o){outcomes=List.of(o);}@Override public AiClientResponse complete(AiPromptRequest r){int i=calls.getAndIncrement();models.add(r.model());retryAttempts.add(r.retryAttempt());Object o=outcomes.get(Math.min(i,outcomes.size()-1));if(o instanceof RuntimeException e)throw e;return new AiClientResponse((String)o,r.model(),10,5);}}
}
