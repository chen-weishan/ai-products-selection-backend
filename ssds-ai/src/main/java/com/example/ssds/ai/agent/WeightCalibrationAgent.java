package com.example.ssds.ai.agent;

import com.example.ssds.ai.access.common.AiModelNotFoundException;
import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.AiPromptRequest;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.policy.ExternalLlmDisabledException;
import com.example.ssds.ai.policy.OutboundDataPolicyException;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.ai.resilience.RetryExecutionState;
import com.example.ssds.ai.resilience.RetrySleeper;
import com.example.ssds.ai.resilience.SafeLogMessage;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.calibration.*;
import com.example.ssds.ai.prompt.calibration.WeightCalibrationPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.calibration.WeightCalibrationResponseParser;
import com.example.ssds.ai.schema.calibration.WeightCalibrationSchema;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Autowired
    public WeightCalibrationAgent(AiAccessRouter router,WeightCalibrationPromptFactory prompts,
            WeightCalibrationResponseParser parser,ObjectMapper mapper,
            MistralModelCatalog modelCatalog,
            @Value("${ai.retry-max:3}") int retryMax) {
        this(router,prompts,parser,mapper,modelCatalog.reasoning().primary(),
                modelCatalog.reasoning().fallbacks(),retryMax,Thread::sleep);
    }
    WeightCalibrationAgent(AiAccessRouter router,WeightCalibrationPromptFactory prompts,
            WeightCalibrationResponseParser parser,ObjectMapper mapper,
            String primary,String fallbacks,int retryMax,RetrySleeper sleeper) {
        this.router=router;this.prompts=prompts;this.parser=parser;this.mapper=mapper;
        this.models=new MistralModelCatalog.ModelChain(primary,fallbacks).models();this.retryMax=Math.max(0,retryMax);this.sleeper=sleeper;
    }
    public WeightCalibrationResult interpret(WeightCalibrationInput input,boolean forceRefresh) {
        if(input.factors().isEmpty()||input.backtests().isEmpty()) return fallback(FallbackReason.DATA_INSUFFICIENT,"not-invoked",0);
        return withRetry(input);
    }
    private WeightCalibrationResult withRetry(WeightCalibrationInput input) {
        RetryExecutionState retry=new RetryExecutionState(models,retryMax);
        while(true) {
            String model=retry.model();
            try {
                log.info("WeightCalibration request: quarter={}, modelAlias=MODEL_REASONING, model={}, promptVersion={}",
                        input.quarter(),model,WeightCalibrationPromptFactory.PROMPT_VERSION);
                String systemPrompt=prompts.systemPrompt()+(retry.retryInstruction()==null?"":"\n\n"+retry.retryInstruction());
                boolean retryAttempt=retry.isRetryAttempt();
                retry.recordRequest();
                AiClientResponse response=router.route(new AiPromptRequest(AiTaskType.WEIGHT_CALIBRATION,model,
                        systemPrompt,prompts.userPrompt(input),WeightCalibrationSchema.create(mapper),retryAttempt));
                WeightCalibrationOutput output=parser.parse(response.content(),input);
                return new WeightCalibrationResult(output,false,null,false,response.model(),
                        WeightCalibrationPromptFactory.PROMPT_VERSION,response.promptTokens(),response.completionTokens(),retry.requestCount());
            } catch(AiSchemaValidationException e) {
                log.warn("WeightCalibration schema invalid: quarter={}, model={}, reason={}",input.quarter(),model,SafeLogMessage.sanitize(e.getMessage()));
                retry.retryInstruction(prompts.retryInstruction(validationCode(e)));
                if(retry.beginSchemaRetry()&&pause(2000))continue;
                if(retry.moveToSchemaFallback())continue;
                return fallback(FallbackReason.SCHEMA_INVALID,model,retry.requestCount());
            } catch(ExternalLlmDisabledException|OutboundDataPolicyException e) {
                log.warn("WeightCalibration external request blocked by policy: quarter={}, reason={}",input.quarter(),SafeLogMessage.sanitize(e.getMessage()));
                return fallback(FallbackReason.AI_UNAVAILABLE,"policy-blocked",Math.max(0,retry.requestCount()-1));
            } catch(AiBudgetExceededException e) {
                throw e;
            } catch(AiRateLimitException e) {
                OptionalLong delay=retry.nextRateLimitDelay();
                if(delay.isPresent()&&pause(delay.getAsLong()))continue;
                if(retry.moveToNextModel())continue;
                return fallback(FallbackReason.AI_UNAVAILABLE,model,retry.requestCount());
            } catch(AiModelNotFoundException|ResourceAccessException e) {
                if(retry.moveToNextModel())continue;
                return fallback(FallbackReason.AI_UNAVAILABLE,model,retry.requestCount());
            } catch(RuntimeException e) {
                log.warn("WeightCalibration request failed: quarter={}, model={}, errorType={}",input.quarter(),model,e.getClass().getSimpleName());
                if(retry.moveToNextModel())continue;
                return fallback(FallbackReason.AI_UNAVAILABLE,model,retry.requestCount());
            }
        }
    }
    private WeightCalibrationResult fallback(FallbackReason reason,String model,int requests) {
        return new WeightCalibrationResult(new WeightCalibrationOutput(
                "AI 解讀未完成，請直接查看統計原始結果表。",List.of(),List.of()),true,reason,false,
                model,WeightCalibrationPromptFactory.PROMPT_VERSION,null,null,requests);
    }
    private boolean pause(long ms){try{sleeper.sleep(ms);return true;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
    private static String validationCode(AiSchemaValidationException e){String m=e.getMessage();if(m==null)return"SCHEMA_INVALID";if(m.contains("report"))return"REPORT_NOT_STRING";if(m.contains("數字"))return"NUMBER_NOT_IN_INPUT";return"SCHEMA_INVALID";}
}
