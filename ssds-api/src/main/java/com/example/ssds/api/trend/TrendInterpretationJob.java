package com.example.ssds.api.trend;

import com.example.ssds.ai.model.TrendInterpreterInput;
import com.example.ssds.ai.prompt.TrendInterpreterPromptFactory;
import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.CreateAiTaskRequest;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每日合成完成後，把首次分析或跨階段／斜率分箱的關鍵字送入 AI task。 */
@Component
@ConditionalOnProperty(name = "ai.trend.schedule-enabled", havingValue = "true")
public class TrendInterpretationJob {
    private static final Logger log = LoggerFactory.getLogger(TrendInterpretationJob.class);
    private static final int TASK_CHUNK_SIZE = 100;

    private final TrendKeywordRepository keywordRepository;
    private final HeatCompositeDailyRepository compositeRepository;
    private final TrendInterpretationRepository interpretationRepository;
    private final AiTaskService taskService;
    private final ObjectMapper objectMapper;

    public TrendInterpretationJob(
            TrendKeywordRepository keywordRepository,
            HeatCompositeDailyRepository compositeRepository,
            TrendInterpretationRepository interpretationRepository,
            AiTaskService taskService,
            ObjectMapper objectMapper) {
        this.keywordRepository = keywordRepository;
        this.compositeRepository = compositeRepository;
        this.interpretationRepository = interpretationRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${ai.trend.schedule-cron:0 10 6 * * *}", zone = "Asia/Taipei")
    public void enqueueSignificantKeywords() {
        List<Long> keywordIds = keywordRepository.findByEnabledTrue().stream()
                .filter(this::isSignificant)
                .map(TrendKeyword::getId)
                .toList();
        for (int from = 0; from < keywordIds.size(); from += TASK_CHUNK_SIZE) {
            List<Long> chunk = keywordIds.subList(
                    from, Math.min(from + TASK_CHUNK_SIZE, keywordIds.size()));
            taskService.create(new CreateAiTaskRequest(
                    AiTaskType.TREND_INTERPRET,
                    List.of(),
                    chunk,
                    new CreateAiTaskRequest.Options(false)));
        }
        log.info("TrendInterpreter daily enqueue completed: keywordCount={}", keywordIds.size());
    }

    private boolean isSignificant(TrendKeyword keyword) {
        Optional<HeatCompositeDaily> latest = compositeRepository
                .findFirstByKeywordIdOrderByStatDateDesc(keyword.getId());
        if (latest.isEmpty()) return false;
        Optional<TrendInterpretation> previous = interpretationRepository
                .findByKeywordIdAndCurrentTrue(keyword.getId());
        if (previous.isEmpty()) return true;
        TrendInterpretation interpretation = previous.get();
        if (!TrendInterpreterPromptFactory.PROMPT_VERSION.equals(
                interpretation.getPromptVersion())) return true;
        if (interpretation.getHeatStage() != latest.get().getStage()) return true;
        return slopeBucket(latest.get().getSlope30d())
                != previousSlopeBucket(interpretation.getInputSnapshot());
    }

    private int previousSlopeBucket(String inputSnapshot) {
        try {
            TrendInterpreterInput input = objectMapper.readValue(
                    inputSnapshot, TrendInterpreterInput.class);
            BigDecimal slope = input.compositeSeries().isEmpty()
                    ? null : input.compositeSeries().getLast().slope30d();
            return slopeBucket(slope);
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("TrendInterpreter input snapshot cannot be read; scheduling refresh");
            return Integer.MAX_VALUE;
        }
    }

    private static int slopeBucket(BigDecimal slope) {
        if (slope == null) return Integer.MIN_VALUE;
        return slope.divide(new BigDecimal("0.10"), 0, RoundingMode.FLOOR).intValue();
    }
}
