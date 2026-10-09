package com.example.ssds.api.admin;

import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.config.MistralModelCatalog.ModelChain;
import com.example.ssds.ai.config.AiRuntimeConfigurable;
import com.example.ssds.ai.resilience.GlobalAiRateLimiter;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.security.CurrentUserId;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.RuntimeSetting;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RuntimeSettingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** S-14 執行期設定的單一寫入點。只接受本類別明列的產品參數。 */
@Service
public class RuntimeSettingsService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeSettingsService.class);
    private static final String AI_KEY = "ai.config";
    private static final String SCHEDULE_KEY = "schedules.config";
    private static final String OPERATIONAL_KEY = "operational.config";
    /** Mistral model ID 只含英數與 {@code . _ : / -}；擋掉空白、逗號等手動輸入誤植。 */
    private static final java.util.regex.Pattern MODEL_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._:/-]{1,100}");
    /** §6.7.2 別名的固定顯示順序；JSON 物件與 Map.copyOf 都不保證順序。 */
    private static final List<String> ALIAS_ORDER = List.of(
            "MODEL_CLASSIFY", "MODEL_LONG_TEXT", "MODEL_SHORT_GEN", "MODEL_NUMERIC", "MODEL_REASONING");

    private final RuntimeSettingRepository settings;
    private final AuditLogRepository audits;
    private final AppUserRepository users;
    private final ObjectMapper mapper;
    private final MistralModelCatalog models;
    private final DailyAiBudget budget;
    private final GlobalAiRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final List<AiRuntimeConfigurable> runtimeConsumers;
    private final List<OperationalRuntimeConfigurable> operationalConsumers;
    private final AiConfig defaultAi;
    private final ScheduleConfig defaultSchedules;
    private final OperationalConfig defaultOperational;

    public RuntimeSettingsService(
            RuntimeSettingRepository settings,
            AuditLogRepository audits,
            AppUserRepository users,
            ObjectMapper mapper,
            MistralModelCatalog models,
            DailyAiBudget budget,
            GlobalAiRateLimiter rateLimiter,
            ApplicationEventPublisher events,
            List<AiRuntimeConfigurable> runtimeConsumers,
            List<OperationalRuntimeConfigurable> operationalConsumers,
            @Value("${mistral.model-classify-primary}") String classifyPrimary,
            @Value("${mistral.model-classify-fallbacks}") String classifyFallbacks,
            @Value("${mistral.model-long-text-primary}") String longTextPrimary,
            @Value("${mistral.model-long-text-fallbacks}") String longTextFallbacks,
            @Value("${mistral.model-short-gen-primary}") String shortPrimary,
            @Value("${mistral.model-short-gen-fallbacks}") String shortFallbacks,
            @Value("${mistral.model-numeric-primary}") String numericPrimary,
            @Value("${mistral.model-numeric-fallbacks}") String numericFallbacks,
            @Value("${mistral.model-reasoning-primary}") String reasoningPrimary,
            @Value("${mistral.model-reasoning-fallbacks}") String reasoningFallbacks,
            @Value("${ai.quota-daily:1000}") int dailyQuota,
            @Value("${ai.quota-share-track-a:0.7}") double trackAShare,
            @Value("${ai.quota-share-track-b:0.2}") double trackBShare,
            @Value("${ai.quota-share-retry:0.1}") double retryShare,
            @Value("${ai.rate-limit-per-minute:20}") int rateLimit,
            @Value("${ai.trend.rate-limit-per-minute:5}") int trendRateLimit,
            @Value("${ai.batch-item-cap:150}") int batchItemCap,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${mistral.timeout-seconds:30}") int timeoutSeconds,
            @Value("${mistral.sourcing-timeout-seconds:90}") int sourcingTimeoutSeconds,
            @Value("${ai.cache-days:6}") int cacheDays,
            @Value("${ai.cache-days-trend:3}") int trendCacheDays,
            @Value("${ai.cache-days-sourcing:3}") int sourcingCacheDays,
            @Value("${ai.full-analysis.schedule-enabled:true}") boolean fullAnalysisEnabled,
            @Value("${ai.full-analysis.schedule-cron:0 0 7 * * MON}") String fullAnalysisCron,
            @Value("${ai.full-analysis.resume-cron:0 0 7 * * TUE-SUN}") String fullAnalysisResumeCron,
            @Value("${scoring.schedule-enabled:false}") boolean scoringEnabled,
            @Value("${scoring.schedule-cron:0 50 6 * * MON}") String scoringCron,
            @Value("${ai.calibration.schedule-enabled:true}") boolean calibrationEnabled,
            @Value("${ai.calibration.schedule-cron:0 0 8,9 1 1,4,7,10 *}") String calibrationCron,
            @Value("${ssds.risk.heat-alert.schedule-enabled:true}") boolean heatAlertEnabled,
            @Value("${ssds.risk.heat-alert.cron:0 30 6 * * *}") String heatAlertCron,
            @Value("${ssds.calibration.heat-composite.cron:0 0 6 * * *}") String heatCompositeCron,
            @Value("${ssds.auth.login-max-failed-attempts:5}") int loginMaxFailedAttempts,
            @Value("${ssds.auth.login-lock-duration:15m}") Duration loginLockDuration,
            @Value("${ssds.heat-tag.halve-after-days:14}") int heatTagHalveAfterDays,
            @Value("${ssds.heat-tag.expire-days:30}") int heatTagExpireDays,
            @Value("${ssds.scoring.min-category-sample:10}") int scoringMinCategorySample,
            @Value("${ssds.scene.adopt-confidence:0.5}") BigDecimal sceneAdoptConfidence,
            @Value("${ssds.scene.scoring-confidence:0.7}") BigDecimal sceneScoringConfidence,
            @Value("${ssds.calibration.min-sample:200}") int calibrationMinSample) {
        this.settings = settings;
        this.audits = audits;
        this.users = users;
        this.mapper = mapper;
        this.models = models;
        this.budget = budget;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.runtimeConsumers = List.copyOf(runtimeConsumers);
        this.operationalConsumers = List.copyOf(operationalConsumers);
        Map<String, ModelRoute> routes = new LinkedHashMap<>();
        routes.put("MODEL_CLASSIFY", new ModelRoute(classifyPrimary, split(classifyFallbacks)));
        routes.put("MODEL_LONG_TEXT", new ModelRoute(longTextPrimary, split(longTextFallbacks)));
        routes.put("MODEL_SHORT_GEN", new ModelRoute(shortPrimary, split(shortFallbacks)));
        routes.put("MODEL_NUMERIC", new ModelRoute(numericPrimary, split(numericFallbacks)));
        routes.put("MODEL_REASONING", new ModelRoute(reasoningPrimary, split(reasoningFallbacks)));
        defaultAi = new AiConfig(routes, dailyQuota, trackAShare, trackBShare, retryShare, 0.8,
                rateLimit, trendRateLimit, batchItemCap, retryMax, timeoutSeconds,
                sourcingTimeoutSeconds, cacheDays, trendCacheDays, sourcingCacheDays);
        defaultSchedules = new ScheduleConfig(List.of(
                new ScheduleItem("FULL_ANALYSIS", "週選品 AI 分析", fullAnalysisCron, fullAnalysisEnabled),
                new ScheduleItem("FULL_ANALYSIS_RESUME", "AI 待重跑續跑", fullAnalysisResumeCron, fullAnalysisEnabled),
                new ScheduleItem("PURE_SCORING", "每週全量評分", scoringCron, scoringEnabled),
                new ScheduleItem("CALIBRATION", "季度權重校準", calibrationCron, calibrationEnabled),
                new ScheduleItem("HEAT_COMPOSITE", "熱度採集與合成", heatCompositeCron, true),
                new ScheduleItem("HEAT_ALERT", "熱度異常示警", heatAlertCron, heatAlertEnabled)));
        defaultOperational = new OperationalConfig(
                loginMaxFailedAttempts,
                Math.toIntExact(loginLockDuration.toMinutes()),
                heatTagHalveAfterDays,
                heatTagExpireDays,
                scoringMinCategorySample,
                sceneAdoptConfidence,
                sceneScoringConfidence,
                calibrationMinSample);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreRuntimeOverrides() {
        applyAi(aiConfig());
        applyOperational(operationalConfig());
    }

    public AiConfig aiConfig() {
        return read(AI_KEY, AiConfig.class, defaultAi);
    }

    @Transactional
    public AiConfig updateAi(AiConfig requested, String sourceIp) {
        validate(requested);
        AiConfig before = aiConfig();
        save(AI_KEY, "AI", requested);
        audit("UPDATE_AI_CONFIG", AI_KEY, before, requested, sourceIp);
        events.publishEvent(new RuntimeAiChanged(requested));
        return requested;
    }

    @Transactional
    public AiConfig updateBudget(BudgetUpdate request, String sourceIp) {
        AiConfig current = aiConfig();
        return updateAi(new AiConfig(
                current.models(), request.dailyQuota(), request.trackAShare(), request.trackBShare(),
                request.retryShare(), request.warningRatio(), current.rateLimitPerMinute(),
                current.trendRateLimitPerMinute(), current.batchItemCap(), current.retryMax(),
                current.timeoutSeconds(), current.sourcingTimeoutSeconds(), current.cacheDays(),
                current.trendCacheDays(), current.sourcingCacheDays()), sourceIp);
    }

    public ScheduleConfig schedules() {
        return read(SCHEDULE_KEY, ScheduleConfig.class, defaultSchedules);
    }

    @Transactional
    public ScheduleConfig updateSchedules(ScheduleConfig requested, String sourceIp) {
        validate(requested);
        // 名稱以伺服器定義為準，cron 去頭尾空白；客戶端只能改 cron 與啟用狀態
        Map<String, String> labels = new LinkedHashMap<>();
        defaultSchedules.items().forEach(item -> labels.put(item.code(), item.label()));
        List<ScheduleItem> incoming = requested.items();
        ScheduleConfig normalized = new ScheduleConfig(defaultSchedules.items().stream()
                .map(item -> incoming.stream().filter(r -> r.code().equals(item.code())).findFirst().orElseThrow())
                .map(item -> new ScheduleItem(item.code(), labels.get(item.code()), item.cron().trim(), item.enabled()))
                .toList());
        return saveSchedules(normalized, sourceIp);
    }

    private ScheduleConfig saveSchedules(ScheduleConfig requested, String sourceIp) {
        ScheduleConfig before = schedules();
        save(SCHEDULE_KEY, "SCHEDULE", requested);
        audit("UPDATE_SCHEDULE_CONFIG", SCHEDULE_KEY, before, requested, sourceIp);
        events.publishEvent(new RuntimeSchedulesChanged(requested));
        return requested;
    }

    public OperationalConfig operationalConfig() {
        return read(OPERATIONAL_KEY, OperationalConfig.class, defaultOperational);
    }

    @Transactional
    public OperationalConfig updateOperational(OperationalConfig requested, String sourceIp) {
        validate(requested);
        OperationalConfig before = operationalConfig();
        save(OPERATIONAL_KEY, "OPERATIONAL", requested);
        audit("UPDATE_OPERATIONAL_CONFIG", OPERATIONAL_KEY, before, requested, sourceIp);
        events.publishEvent(new RuntimeOperationalChanged(requested));
        return requested;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aiSettingsChanged(RuntimeAiChanged event) {
        applyAi(event.config());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void operationalSettingsChanged(RuntimeOperationalChanged event) {
        applyOperational(event.config());
    }

    private void applyAi(AiConfig config) {
        models.replace(
                chain(config, "MODEL_CLASSIFY"), chain(config, "MODEL_LONG_TEXT"),
                chain(config, "MODEL_SHORT_GEN"), chain(config, "MODEL_NUMERIC"),
                chain(config, "MODEL_REASONING"));
        budget.reconfigure(config.dailyQuota(), config.trackAShare(), config.trackBShare(),
                config.retryShare(), config.warningRatio());
        rateLimiter.reconfigure(config.rateLimitPerMinute(), config.trendRateLimitPerMinute());
        runtimeConsumers.forEach(consumer -> consumer.reconfigure(
                models, config.retryMax(), config.cacheDays(),
                config.trendCacheDays(), config.sourcingCacheDays(), config.batchItemCap(),
                config.timeoutSeconds(), config.sourcingTimeoutSeconds()));
    }

    private void applyOperational(OperationalConfig config) {
        runtimeConsumers.forEach(consumer ->
                consumer.reconfigureSceneAdoptConfidence(config.sceneAdoptConfidence()));
        operationalConsumers.forEach(consumer -> consumer.reconfigure(config));
    }

    private ModelChain chain(AiConfig config, String alias) {
        ModelRoute route = config.models().get(alias);
        return new ModelChain(route.primary(), String.join(",", route.fallbacks()));
    }

    private void validate(AiConfig config) {
        if (config == null || config.models() == null || !config.models().keySet().equals(defaultAi.models().keySet())) {
            throw invalid("模型別名必須完整且不可新增未支援的別名");
        }
        config.models().forEach((alias, route) -> {
            if (route == null || route.primary() == null || route.primary().isBlank()) {
                throw invalid(alias + " 的主要模型不得為空");
            }
            java.util.stream.Stream.concat(java.util.stream.Stream.of(route.primary()), route.fallbacks().stream())
                    .filter(model -> model == null || !MODEL_ID.matcher(model.trim()).matches())
                    .findFirst()
                    .ifPresent(model -> {
                        throw invalid(alias + " 的模型名稱格式不正確：" + model);
                    });
        });
        if (config.dailyQuota() < 0 || config.batchItemCap() < 1 || config.retryMax() < 0) {
            throw invalid("配額、批次上限或重試次數不合法");
        }
        if (config.timeoutSeconds() < 1 || config.sourcingTimeoutSeconds() < 1
                || config.cacheDays() < 0 || config.trendCacheDays() < 0 || config.sourcingCacheDays() < 0) {
            throw invalid("逾時與快取天數不合法");
        }
        if (config.rateLimitPerMinute() < 1 || config.trendRateLimitPerMinute() < 1) {
            throw invalid("每分鐘請求上限必須大於 0");
        }
        double total = config.trackAShare() + config.trackBShare() + config.retryShare();
        if (config.trackAShare() < 0 || config.trackBShare() < 0 || config.retryShare() < 0
                || Math.abs(total - 1d) > 0.000001d) {
            throw invalid("三個預算池比例總和必須等於 1");
        }
        if (config.warningRatio() <= 0 || config.warningRatio() >= 1) {
            throw invalid("配額警示比例必須大於 0 且小於 1");
        }
    }

    private void validate(ScheduleConfig config) {
        if (config == null || config.items() == null || config.items().isEmpty()) {
            throw invalid("排程清單不得為空");
        }
        Map<String, ScheduleItem> allowed = new LinkedHashMap<>();
        defaultSchedules.items().forEach(item -> allowed.put(item.code(), item));
        if (config.items().size() != allowed.size()
                || !new HashSet<>(config.items().stream().map(ScheduleItem::code).toList())
                        .equals(allowed.keySet())) {
            throw invalid("排程清單必須完整");
        }
        for (ScheduleItem item : config.items()) {
            if (!allowed.containsKey(item.code())) throw invalid("不支援的排程：" + item.code());
            if (item.cron() == null || !CronExpression.isValidExpression(item.cron().trim())) {
                throw invalid(item.label() + " 的排程格式不正確：" + item.cron());
            }
        }
    }

    private void validate(OperationalConfig config) {
        if (config == null
                || config.loginMaxFailedAttempts() < 1
                || config.loginLockDurationMinutes() < 1
                || config.heatTagHalveAfterDays() < 1
                || config.heatTagExpireDays() <= config.heatTagHalveAfterDays()
                || config.scoringMinCategorySample() < 1
                || config.calibrationMinSample() < 1) {
            throw invalid("營運參數必須為正數，且熱度標記失效天數須大於減半天數");
        }
        if (outsideUnitInterval(config.sceneAdoptConfidence())
                || outsideUnitInterval(config.sceneScoringConfidence())
                || config.sceneScoringConfidence().compareTo(config.sceneAdoptConfidence()) < 0) {
            throw invalid("情境信心門檻須介於 0 到 1，且計分門檻不得低於採用門檻");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private boolean outsideUnitInterval(BigDecimal value) {
        return value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0;
    }

    private <T> T read(String key, Class<T> type, T fallback) {
        try {
            return settings.findById(key).map(setting -> {
                try {
                    return mapper.readValue(setting.getValueJson(), type);
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("執行期設定格式損毀：" + key, exception);
                }
            }).orElse(fallback);
        } catch (DataAccessException exception) {
            log.warn("runtime_setting 尚不可用，{} 暫時使用 properties 預設值", key);
            return fallback;
        }
    }

    private void save(String key, String group, Object value) {
        RuntimeSetting setting = settings.findById(key).orElseGet(RuntimeSetting::new);
        setting.setKey(key);
        setting.setGroupName(group);
        setting.setValueJson(write(value));
        setting.setUpdatedBy(users.getReferenceById(CurrentUserId.require()));
        setting.setUpdatedAt(Instant.now());
        settings.save(setting);
    }

    private void audit(String action, String key, Object before, Object after, String sourceIp) {
        audits.save(AuditLog.builder()
                .user(users.getReferenceById(CurrentUserId.require()))
                .action(action)
                .entityType("RuntimeSetting")
                .beforeJson(write(before))
                .afterJson(write(after))
                .ip(sourceIp)
                .build());
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化執行期設定", exception);
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    public record ModelRoute(String primary, List<String> fallbacks) {
        /** 去頭尾空白、去重，並移除與主模型相同的備援，避免同一模型重試兩次。 */
        public ModelRoute {
            primary = primary == null ? null : primary.trim();
            String main = primary;
            fallbacks = fallbacks == null ? List.of() : fallbacks.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(model -> !model.isEmpty() && !model.equals(main))
                    .distinct()
                    .toList();
        }
    }

    public record AiConfig(
            Map<String, ModelRoute> models,
            int dailyQuota,
            double trackAShare,
            double trackBShare,
            double retryShare,
            double warningRatio,
            int rateLimitPerMinute,
            int trendRateLimitPerMinute,
            int batchItemCap,
            int retryMax,
            int timeoutSeconds,
            int sourcingTimeoutSeconds,
            int cacheDays,
            int trendCacheDays,
            int sourcingCacheDays) {
        public AiConfig {
            Map<String, ModelRoute> ordered = new LinkedHashMap<>();
            if (models != null) {
                for (String alias : ALIAS_ORDER) {
                    if (models.containsKey(alias)) ordered.put(alias, models.get(alias));
                }
                models.forEach(ordered::putIfAbsent);
            }
            models = java.util.Collections.unmodifiableMap(ordered);
        }
    }
    public record BudgetUpdate(
            int dailyQuota,
            double trackAShare,
            double trackBShare,
            double retryShare,
            double warningRatio) {}

    public record ScheduleItem(String code, String label, String cron, boolean enabled) {}
    public record ScheduleConfig(List<ScheduleItem> items) {
        public ScheduleConfig {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
    public record RuntimeSchedulesChanged(ScheduleConfig config) {}
    public record RuntimeAiChanged(AiConfig config) {}
    public record OperationalConfig(
            int loginMaxFailedAttempts,
            int loginLockDurationMinutes,
            int heatTagHalveAfterDays,
            int heatTagExpireDays,
            int scoringMinCategorySample,
            BigDecimal sceneAdoptConfidence,
            BigDecimal sceneScoringConfidence,
            int calibrationMinSample) {}
    public record RuntimeOperationalChanged(OperationalConfig config) {}
}
