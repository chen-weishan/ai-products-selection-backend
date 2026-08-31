package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.WeightCalibrationPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.WeightCalibrationResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class WeightCalibrationAgentTest {
    @Test void retryUsesSameModelThenFallbackAndReturnsValidOutput(){
        FakeClient client=new FakeClient("{\"invalid\":true}","{\"invalid\":true}",validJson());
        WeightCalibrationResult result=agent(client).interpret(input(),false);
        assertFalse(result.fallbackApplied()); assertEquals(3,result.requestCount());
        assertEquals(List.of("fake/primary","fake/primary","fake/fallback"),client.models);
    }
    @Test void repeatedFailureFallsBackToRawStatisticsMessage(){
        FakeClient client=new FakeClient(new IllegalStateException("offline"));
        WeightCalibrationResult result=agent(client).interpret(input(),false);
        assertTrue(result.fallbackApplied());
        assertEquals("AI 解讀未完成，請直接查看統計原始結果表。",result.output().report());
        assertTrue(result.output().adjustmentAdvice().isEmpty());
    }
    @Test void successfulResultIsCachedPerQuarterAndInput(){
        FakeClient client=new FakeClient(validJson()); WeightCalibrationAgent agent=agent(client);
        assertFalse(agent.interpret(input(),false).cacheHit()); assertTrue(agent.interpret(input(),false).cacheHit());
        assertEquals(1,client.calls.get());
    }
    private static WeightCalibrationAgent agent(TrackAAiClient client){ObjectMapper m=new ObjectMapper();return new WeightCalibrationAgent(
            new AiAccessRouter(client),new WeightCalibrationPromptFactory(m),new WeightCalibrationResponseParser(m),m,
            new TrackRetryBudget(1000,0.1),"fake/primary","fake/fallback",3,6,ms->{});}
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
    private static final class FakeClient implements TrackAAiClient {final List<Object> outcomes;final AtomicInteger calls=new AtomicInteger();final List<String>models=new ArrayList<>();
        FakeClient(Object...o){outcomes=List.of(o);}@Override public AiClientResponse complete(AiPromptRequest r){int i=calls.getAndIncrement();models.add(r.model());Object o=outcomes.get(Math.min(i,outcomes.size()-1));if(o instanceof RuntimeException e)throw e;return new AiClientResponse((String)o,r.model(),10,5);}}
}
