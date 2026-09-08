package com.example.ssds.api.trend;

import com.example.ssds.ai.agent.TrendInterpreterAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.*;
import com.example.ssds.api.common.error.*;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import com.example.ssds.api.sourcing.SourcingTimeGapRecalculationService;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrendInterpretationService {
    private static final Logger log = LoggerFactory.getLogger(TrendInterpretationService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final int WINDOW_DAYS = 90;

    private final TrendKeywordRepository keywordRepository;
    private final HeatCompositeDailyRepository compositeRepository;
    private final HeatReadingRepository readingRepository;
    private final TrendInterpretationRepository interpretationRepository;
    private final PromptSanitizer promptSanitizer;
    private final TrendInterpreterAgent agent;
    private final ObjectMapper objectMapper;
    private final SourcingTimeGapRecalculationService sourcingTimeGapRecalculationService;

    public TrendInterpretationService(
            TrendKeywordRepository keywordRepository,
            HeatCompositeDailyRepository compositeRepository,
            HeatReadingRepository readingRepository,
            TrendInterpretationRepository interpretationRepository,
            PromptSanitizer promptSanitizer,
            TrendInterpreterAgent agent,
            ObjectMapper objectMapper,
            SourcingTimeGapRecalculationService sourcingTimeGapRecalculationService) {
        this.keywordRepository = keywordRepository;
        this.compositeRepository = compositeRepository;
        this.readingRepository = readingRepository;
        this.interpretationRepository = interpretationRepository;
        this.promptSanitizer = promptSanitizer;
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.sourcingTimeGapRecalculationService = sourcingTimeGapRecalculationService;
    }

    @Transactional
    public TrendInterpretationResponse interpret(Long keywordId, boolean forceRefresh) {
        TrendKeyword keyword = loadKeyword(keywordId);
        HeatCompositeDaily latest = compositeRepository
                .findFirstByKeywordIdOrderByStatDateDesc(keywordId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "此關鍵字尚無合成熱度資料"));
        LocalDate from = latest.getStatDate().minusDays(WINDOW_DAYS - 1L);
        List<HeatCompositeDaily> composites = compositeRepository
                .findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
                        keywordId, from, latest.getStatDate());
        List<HeatReading> readings = readingRepository
                .findForKeywordIncludingCategorySources(
                        keywordId, from, latest.getStatDate());
        TrendInterpreterInput input = promptSanitizer.sanitizeTrendInterpreter(
                buildInput(keywordId, latest.getStatDate(), composites, readings));
        TrendInterpreterResult result = agent.interpret(input, forceRefresh);
        Instant generatedAt = Instant.now();
        applyOutput(latest, result.output());
        persistHistory(keyword, input, result, generatedAt);
        sourcingTimeGapRecalculationService.recalculateAffectedByKeyword(keywordId);
        log.info(
                "TrendInterpreter completed: keywordId={}, promptVersion={}, modelAlias=MODEL_NUMERIC, fallback={}, cacheHit={}",
                keywordId, result.promptVersion(), result.fallbackApplied(), result.cacheHit());
        return TrendInterpretationResponse.from(
                keywordId, result, generatedAt.atZone(BUSINESS_ZONE).toOffsetDateTime());
    }

    @Transactional(readOnly = true)
    public TrendInterpretationResponse latest(Long keywordId) {
        loadKeyword(keywordId);
        TrendInterpretation value = interpretationRepository
                .findByKeywordIdAndCurrentTrue(keywordId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "此關鍵字尚無趨勢解讀"));
        return new TrendInterpretationResponse(
                keywordId,
                value.getHeatStage(),
                value.getStageWeeks(),
                value.getEstimatedLifespanDays(),
                value.isFallbackApplied(),
                value.getFallbackReason(),
                false,
                value.getModel(),
                "MODEL_NUMERIC",
                value.getPromptVersion(),
                0,
                value.getGeneratedAt().atZone(BUSINESS_ZONE).toOffsetDateTime());
    }

    private TrendKeyword loadKeyword(Long keywordId) {
        return keywordRepository.findById(keywordId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的趨勢關鍵字"));
    }

    private TrendInterpreterInput buildInput(
            Long keywordId,
            LocalDate latestDate,
            List<HeatCompositeDaily> composites,
            List<HeatReading> readings) {
        List<TrendInterpreterInput.CompositePoint> points = composites.stream()
                .map(value -> new TrendInterpreterInput.CompositePoint(
                        value.getStatDate().toString(), value.getCompositeValue(),
                        value.getSlope7d(), value.getSlope30d()))
                .toList();
        List<TrendInterpreterInput.SourceTrend> sourceTrends = readings.stream()
                .collect(Collectors.groupingBy(
                        SourceKey::from,
                        Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing(SourceKey::source)
                        .thenComparing(SourceKey::categoryId, Comparator.nullsFirst(Long::compareTo))))
                .map(entry -> sourceTrend(entry.getKey(), entry.getValue()))
                .toList();
        List<TrendInterpreterInput.AllowedOutput> allowed = Arrays.stream(HeatStage.values())
                .map(stage -> {
                    int weeks = candidateStageWeeks(composites, latestDate, stage);
                    return new TrendInterpreterInput.AllowedOutput(
                            stage, weeks, lifespan(stage, weeks));
                })
                .toList();
        return new TrendInterpreterInput(keywordId, points, sourceTrends, allowed);
    }

    private static TrendInterpreterInput.SourceTrend sourceTrend(
            SourceKey source, List<HeatReading> readings) {
        List<HeatReading> sorted = readings.stream()
                .filter(value -> value.getPercentileWithinSource() != null)
                .sorted(Comparator.comparing(HeatReading::getReadingDate))
                .toList();
        HeatReading latest = readings.stream()
                .max(Comparator.comparing(HeatReading::getReadingDate))
                .orElseThrow();
        return new TrendInterpreterInput.SourceTrend(
                source.source(),
                source.granularity(),
                source.categoryId(),
                slope(sorted, 7),
                slope(sorted, 30),
                latest.getSource().getAvailability());
    }

    private record SourceKey(HeatSourceCode source, HeatGranularity granularity, Long categoryId) {
        private static SourceKey from(HeatReading reading) {
            return new SourceKey(
                    reading.getSource().getSourceCode(),
                    reading.getSource().getGranularity(),
                    reading.getCategory() == null ? null : reading.getCategory().getId());
        }
    }

    private static BigDecimal slope(List<HeatReading> readings, int days) {
        if (readings.isEmpty()) return null;
        HeatReading latest = readings.getLast();
        LocalDate target = latest.getReadingDate().minusDays(days);
        HeatReading previous = readings.stream()
                .filter(value -> !value.getReadingDate().isAfter(target))
                .max(Comparator.comparing(HeatReading::getReadingDate))
                .orElse(null);
        if (previous == null) return null;
        BigDecimal denominator = previous.getPercentileWithinSource().max(BigDecimal.ONE);
        return latest.getPercentileWithinSource()
                .subtract(previous.getPercentileWithinSource())
                .divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static int candidateStageWeeks(
            List<HeatCompositeDaily> composites, LocalDate latestDate, HeatStage candidate) {
        List<HeatCompositeDaily> prior = composites.stream()
                .filter(value -> value.getStatDate().isBefore(latestDate))
                .sorted(Comparator.comparing(HeatCompositeDaily::getStatDate))
                .toList();
        if (prior.isEmpty() || prior.getLast().getStage() != candidate) return 1;
        LocalDate started = prior.getLast().getStatDate();
        for (int index = prior.size() - 2; index >= 0; index--) {
            HeatCompositeDaily value = prior.get(index);
            if (value.getStage() != candidate) break;
            started = value.getStatDate();
        }
        return Math.max(1, Math.toIntExact(ChronoUnit.DAYS.between(started, latestDate) / 7 + 1));
    }

    private static int lifespan(HeatStage stage, int stageWeeks) {
        return switch (stage) {
            case RISING -> 56;
            case PLATEAU -> stageWeeks <= 2 ? 42 : 35;
            case DECLINING -> 17;
        };
    }

    private void applyOutput(HeatCompositeDaily latest, TrendInterpreterOutput output) {
        latest.setStage(output.stage());
        latest.setStageWeeks((short) output.stageWeeks());
        latest.setEstimatedLifespanDays(output.estimatedLifespanDays());
        latest.setStageSource(HeatValueSource.AGENT);
        latest.setLifespanSource(HeatValueSource.AGENT);
        compositeRepository.save(latest);
    }

    private void persistHistory(
            TrendKeyword keyword,
            TrendInterpreterInput input,
            TrendInterpreterResult result,
            Instant generatedAt) {
        interpretationRepository.demoteCurrent(keyword.getId());
        interpretationRepository.flush();
        interpretationRepository.save(TrendInterpretation.builder()
                .keyword(keyword)
                .heatStage(result.output().stage())
                .stageWeeks((short) result.output().stageWeeks())
                .estimatedLifespanDays(result.output().estimatedLifespanDays())
                .inputSnapshot(writeInput(input))
                .fallbackApplied(result.fallbackApplied())
                .fallbackReason(result.fallbackReason() == null
                        ? null : result.fallbackReason().name())
                .model(result.fallbackApplied() ? "rule-fallback" : result.model())
                .promptVersion(TrendInterpreterPromptFactory.PROMPT_VERSION)
                .generatedAt(generatedAt)
                .current(true)
                .build());
    }

    private String writeInput(TrendInterpreterInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化 TrendInterpreter 輸入快照", exception);
        }
    }
}
