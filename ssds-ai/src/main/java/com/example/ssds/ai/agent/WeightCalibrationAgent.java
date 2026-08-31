package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.WeightCalibrationPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.*;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class WeightCalibrationAgent {
    private static final Logger log=LoggerFactory.getLogger(WeightCalibrationAgent.class);
    private final AiAccessRouter router; private final WeightCalibrationPromptFactory prompts;
    private final WeightCalibrationResponseParser parser; private final ObjectMapper mapper;
    private final List<String> models; private final int retryMax; private final RetrySleeper sleeper;
    private final TrackRetryBudget budget;
    private final Cache<CacheKey,WeightCalibrationResult> cache;

    @Autowired
    public WeightCalibrationAgent(AiAccessRouter router,WeightCalibrationPromptFactory prompts,
            WeightCalibrationResponseParser parser,ObjectMapper mapper,
            TrackRetryBudget budget,
            @Value("${mistral.model-reasoning-primary:mistral-medium-3-5}") String primary,
            @Value("${mistral.model-reasoning-fallbacks:mistral-small-latest}") String fallbacks,
            @Value("${ai.retry-max:3}") int retryMax,@Value("${ai.cache-days:6}") long cacheDays) {
        this(router,prompts,parser,mapper,budget,primary,fallbacks,retryMax,cacheDays,Thread::sleep);
    }
    WeightCalibrationAgent(AiAccessRouter router,WeightCalibrationPromptFactory prompts,
            WeightCalibrationResponseParser parser,ObjectMapper mapper,TrackRetryBudget budget,
            String primary,String fallbacks,int retryMax,long cacheDays,RetrySleeper sleeper) {
        this.router=router;this.prompts=prompts;this.parser=parser;this.mapper=mapper;
        this.models=parseModels(primary,fallbacks);this.retryMax=Math.max(0,retryMax);this.sleeper=sleeper;this.budget=budget;
        this.cache=Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(cacheDays)).maximumSize(100).build();
    }
    public WeightCalibrationResult interpret(WeightCalibrationInput input,boolean forceRefresh) {
        CacheKey key=new CacheKey(input.quarter(),input.hashCode(),WeightCalibrationPromptFactory.PROMPT_VERSION);
        if(!forceRefresh) { var found=cache.getIfPresent(key);if(found!=null)return found.asCacheHit(); }
        if(input.factors().isEmpty()||input.backtests().isEmpty()) return fallback(FallbackReason.DATA_INSUFFICIENT,"not-invoked",0);
        WeightCalibrationResult result=withRetry(input); if(!result.fallbackApplied())cache.put(key,result); return result;
    }
    private WeightCalibrationResult withRetry(WeightCalibrationInput input) {
        int modelIndex=0,rateRetries=0,schemaRetries=0,requests=0;
        String retryInstruction=null;
        while(true) {
            String model=models.get(modelIndex);
            try {
                log.info("WeightCalibration request: quarter={}, modelAlias=MODEL_REASONING, model={}, promptVersion={}",
                        input.quarter(),model,WeightCalibrationPromptFactory.PROMPT_VERSION);
                budget.acquire(); requests++;
                String systemPrompt=prompts.systemPrompt()+(retryInstruction==null?"":"\n\n"+retryInstruction);
                AiClientResponse response=router.route(new AiPromptRequest(AiTaskType.WEIGHT_CALIBRATION,model,
                        systemPrompt,prompts.userPrompt(input),WeightCalibrationSchema.create(mapper)));
                WeightCalibrationOutput output=parser.parse(response.content(),input);
                return new WeightCalibrationResult(output,false,null,false,response.model(),
                        WeightCalibrationPromptFactory.PROMPT_VERSION,response.promptTokens(),response.completionTokens(),requests);
            } catch(AiSchemaValidationException e) {
                log.warn("WeightCalibration schema invalid: quarter={}, model={}, reason={}",input.quarter(),model,safe(e.getMessage()));
                retryInstruction=prompts.retryInstruction(validationCode(e));
                if(schemaRetries==0){schemaRetries++;if(pause(2000))continue;}
                if(schemaRetries==1&&hasFallback(modelIndex)){schemaRetries++;modelIndex++;continue;}
                return fallback(FallbackReason.SCHEMA_INVALID,model,requests);
            } catch(RetryBudgetExceededException e) {
                return fallback(FallbackReason.AI_UNAVAILABLE,model,requests);
            } catch(AiRateLimitException e) {
                if(rateRetries<retryMax){long delay=1000L<<Math.min(rateRetries++,10);if(pause(delay))continue;}
                if(hasFallback(modelIndex)){modelIndex++;continue;}
                return fallback(FallbackReason.AI_UNAVAILABLE,model,requests);
            } catch(AiModelNotFoundException|ResourceAccessException e) {
                if(hasFallback(modelIndex)){modelIndex++;continue;}
                return fallback(FallbackReason.AI_UNAVAILABLE,model,requests);
            } catch(RuntimeException e) {
                log.warn("WeightCalibration request failed: quarter={}, model={}, errorType={}",input.quarter(),model,e.getClass().getSimpleName());
                if(hasFallback(modelIndex)){modelIndex++;continue;}
                return fallback(FallbackReason.AI_UNAVAILABLE,model,requests);
            }
        }
    }
    private WeightCalibrationResult fallback(FallbackReason reason,String model,int requests) {
        return new WeightCalibrationResult(new WeightCalibrationOutput(
                "AI 解讀未完成，請直接查看統計原始結果表。",List.of(),List.of()),true,reason,false,
                model,WeightCalibrationPromptFactory.PROMPT_VERSION,null,null,requests);
    }
    private boolean pause(long ms){try{sleeper.sleep(ms);return true;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
    private boolean hasFallback(int index){return index==0&&models.size()>1;}
    private static List<String> parseModels(String primary,String fallbacks){LinkedHashSet<String>s=new LinkedHashSet<>();
        if(primary!=null&&!primary.isBlank())s.add(primary.trim());if(fallbacks!=null)Arrays.stream(fallbacks.split(",")).map(String::trim).filter(v->!v.isBlank()).forEach(s::add);
        if(s.isEmpty())throw new IllegalArgumentException("至少必須設定一個 WeightCalibration 模型");return List.copyOf(s);}
    private static String safe(String m){if(m==null)return"unavailable";String v=m.replace('\r',' ').replace('\n',' ');return v.length()<=160?v:v.substring(0,160);}
    private static String validationCode(AiSchemaValidationException e){String m=e.getMessage();if(m==null)return"SCHEMA_INVALID";if(m.contains("report"))return"REPORT_NOT_STRING";if(m.contains("數字"))return"NUMBER_NOT_IN_INPUT";return"SCHEMA_INVALID";}
    private record CacheKey(String quarter,int inputHash,String promptVersion){}
    @FunctionalInterface interface RetrySleeper{void sleep(long millis)throws InterruptedException;}
}
