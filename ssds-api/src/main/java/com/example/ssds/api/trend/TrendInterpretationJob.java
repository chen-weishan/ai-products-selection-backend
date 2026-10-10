package com.example.ssds.api.trend;

import com.example.ssds.ai.model.trend.TrendInterpreterInput;
import com.example.ssds.ai.prompt.trend.TrendInterpreterPromptFactory;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 每日合成完成後，把首次分析或跨階段／斜率分箱的關鍵字送入 AI task。 */
@Component
public class TrendInterpretationJob {
    private static final Logger log = LoggerFactory.getLogger(TrendInterpretationJob.class);
    private static final int TASK_CHUNK_SIZE = 100;

    private final TrendKeywordRepository keywordRepository;
    private final HeatCompositeDailyRepository compositeRepository;
    private final TrendInterpretationRepository interpretationRepository;
    private final AiTaskService taskService;
    private final ObjectMapper objectMapper;
    private volatile boolean enabled;

    @Autowired
    public TrendInterpretationJob(
            TrendKeywordRepository keywordRepository,
            HeatCompositeDailyRepository compositeRepository,
            TrendInterpretationRepository interpretationRepository,
            AiTaskService taskService,
            ObjectMapper objectMapper,
            @Value("${ai.trend.schedule-enabled:true}") boolean enabled) {
        this.keywordRepository = keywordRepository;
        this.compositeRepository = compositeRepository;
        this.interpretationRepository = interpretationRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public TrendInterpretationJob(
            TrendKeywordRepository keywordRepository,
            HeatCompositeDailyRepository compositeRepository,
            TrendInterpretationRepository interpretationRepository,
            AiTaskService taskService,
            ObjectMapper objectMapper) {
        this(keywordRepository, compositeRepository, interpretationRepository, taskService, objectMapper, true);
    }

    public void reconfigure(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<Long> enqueueSignificantKeywords(LocalDate businessDate) {
        if (!enabled) return Set.of();
        return enqueueSignificantKeywords(businessDate, keywordRepository.findByEnabledTrue());
    }

    public Set<Long> enqueueSignificantKeywords(
            LocalDate businessDate, Collection<Long> keywordIds) {
        if (!enabled) return Set.of();
        List<TrendKeyword> keywords = keywordRepository.findAllById(keywordIds).stream()
                .filter(TrendKeyword::isEnabled)
                .toList();
        return enqueueSignificantKeywords(businessDate, keywords);
    }

    /** 歷史回補使用：七日中任一天符合條件，就為該關鍵字排入一次 Agent 5。 */
    public Set<Long> enqueueSignificantKeywordsForDates(
            Collection<LocalDate> businessDates, Collection<Long> keywordIds) {
        if (!enabled || businessDates.isEmpty()) return Set.of();
        List<LocalDate> dates = businessDates.stream().distinct().sorted().toList();
        List<TrendKeyword> keywords = keywordRepository.findAllById(keywordIds).stream()
                .filter(TrendKeyword::isEnabled)
                .toList();
        List<Long> significantKeywordIds = new ArrayList<>();
        for (TrendKeyword keyword : keywords) {
            try {
                if (dates.stream().anyMatch(date -> isSignificantOnDate(keyword, date))) {
                    significantKeywordIds.add(keyword.getId());
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "TrendInterpreter backfill significance check failed; skipping keywordId={}",
                        keyword.getId(),
                        exception);
            }
        }
        return enqueue(significantKeywordIds);
    }

    private Set<Long> enqueueSignificantKeywords(
            LocalDate businessDate, List<TrendKeyword> keywords) {
        List<Long> keywordIds = new ArrayList<>();
        for (TrendKeyword keyword : keywords) {
            try {
                if (isSignificant(keyword, businessDate)) {
                    keywordIds.add(keyword.getId());
                }
            } catch (Exception exception) {
                log.warn(
                        "TrendInterpreter significance check failed; skipping keywordId={}",
                        keyword.getId(),
                        exception);
            }
        }
        return enqueue(keywordIds);
    }

    private Set<Long> enqueue(List<Long> keywordIds) {
        Set<Long> deferredKeywordIds = new LinkedHashSet<>();
        for (int from = 0; from < keywordIds.size(); from += TASK_CHUNK_SIZE) {
            List<Long> chunk = keywordIds.subList(
                    from, Math.min(from + TASK_CHUNK_SIZE, keywordIds.size()));
            try {
                taskService.createScheduledTrendInterpretation(chunk);
                // Optional.empty 表示關鍵字已在執行中的任務內，仍必須等該任務完成。
                deferredKeywordIds.addAll(chunk);
            } catch (RuntimeException exception) {
                log.warn(
                        "TrendInterpreter task enqueue failed; using rule baseline for keywordIds={}",
                        chunk,
                        exception);
            }
        }
        log.info(
                "TrendInterpreter daily enqueue completed: keywordCount={}, deferredTimeGapCount={}",
                keywordIds.size(),
                deferredKeywordIds.size());
        return Collections.unmodifiableSet(deferredKeywordIds);
    }

    private boolean isSignificant(TrendKeyword keyword, LocalDate businessDate) {
        Optional<HeatCompositeDaily> latest = compositeRepository
                .findFirstByKeywordIdOrderByStatDateDesc(keyword.getId());
        if (latest.isEmpty() || !businessDate.equals(latest.get().getStatDate())) return false;
        return isSignificant(keyword, latest.get());
    }

    private boolean isSignificantOnDate(TrendKeyword keyword, LocalDate businessDate) {
        return compositeRepository.findByKeywordIdAndStatDate(keyword.getId(), businessDate)
                .map(composite -> isSignificant(keyword, composite))
                .orElse(false);
    }

    private boolean isSignificant(TrendKeyword keyword, HeatCompositeDaily latest) {
        Optional<TrendInterpretation> previous = interpretationRepository
                .findByKeywordIdAndCurrentTrue(keyword.getId());
        if (previous.isEmpty()) return true;
        TrendInterpretation interpretation = previous.get();
        if (!TrendInterpreterPromptFactory.PROMPT_VERSION.equals(
                interpretation.getPromptVersion())) return true;
        if (interpretation.getHeatStage() != latest.getStage()) return true;
        return slopeBucket(latest.getSlope30d())
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
