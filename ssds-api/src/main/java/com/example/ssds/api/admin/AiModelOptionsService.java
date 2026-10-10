package com.example.ssds.api.admin;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * S-14 AI 設定的下拉選項：§6.7.2 邏輯別名說明＋可選模型清單。
 *
 * <p>模型清單優先向 Mistral {@code GET /models} 查帳號實際可用且支援 reasoning 的對話模型（快取 10 分鐘）；
 * 未設金鑰或查詢失敗時顯示 {@code mistral.model-options} 設定清單，但標為尚未驗證、不可直接選用。
 * 目前設定中的模型一律併入，避免既有模型因不在清單內而無法辨識與修正。
 */
@Service
public class AiModelOptionsService {
    private static final Logger log = LoggerFactory.getLogger(AiModelOptionsService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    static final List<AliasInfo> ALIASES = List.of(
            new AliasInfo("MODEL_CLASSIFY", "情境判定", "高頻、短輸入、結構化輸出",
                    List.of("SceneClassifierAgent")),
            new AliasInfo("MODEL_LONG_TEXT", "長文本分析", "評論風險分類、賣點與風險萃取",
                    List.of("ReviewRiskAgent", "ProductInsightAgent")),
            new AliasInfo("MODEL_SHORT_GEN", "短文字生成", "進貨建議",
                    List.of("RecommendationAgent")),
            new AliasInfo("MODEL_NUMERIC", "數值判讀", "趨勢階段與壽命推估",
                    List.of("TrendInterpreterAgent")),
            new AliasInfo("MODEL_REASONING", "高階推理", "尋源探索與權重校準解讀",
                    List.of("SourcingScoutAgent", "WeightCalibrationAgent")));

    private final RuntimeSettingsService settings;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final List<String> configuredOptions;
    private final RestClient client;
    private final Clock clock;
    private final Map<String, Instant> verifiedReasoningModels = new ConcurrentHashMap<>();
    private volatile CachedModels cache;

    @Autowired
    public AiModelOptionsService(
            RuntimeSettingsService settings,
            ObjectMapper mapper,
            @Value("${mistral.base-url:https://api.mistral.ai/v1}") String baseUrl,
            @Value("${mistral.api-key:}") String apiKey,
            @Value("${mistral.model-options:}") String configuredOptions) {
        this(settings, mapper, apiKey, configuredOptions, buildClient(baseUrl), Clock.systemUTC());
    }

    AiModelOptionsService(
            RuntimeSettingsService settings,
            ObjectMapper mapper,
            String apiKey,
            String configuredOptions,
            RestClient client,
            Clock clock) {
        this.settings = settings;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.configuredOptions = split(configuredOptions);
        this.client = client;
        this.clock = clock;
    }

    public AiConfigOptions options() {
        CachedModels models = availableModels();
        Set<String> inUse = new LinkedHashSet<>();
        settings.aiConfig().models().values().forEach(route -> {
            inUse.add(route.primary());
            inUse.addAll(route.fallbacks());
        });
        Set<String> ids = new LinkedHashSet<>(models.ids());
        ids.addAll(inUse);
        List<ModelOption> options = ids.stream()
                .map(id -> new ModelOption(id, models.verified() && models.ids().contains(id), inUse.contains(id)))
                .toList();
        return new AiConfigOptions(ALIASES, options, models.source(), models.warning());
    }

    /** S-14 變更模型路由時逐一向 Mistral 確認 reasoning 能力；未知或不合格都不允許套用。 */
    public void validateReasoningModelsIfChanged(
            RuntimeSettingsService.AiConfig current,
            RuntimeSettingsService.AiConfig requested) {
        if (current != null && current.models().equals(requested.models())) return;
        validateReasoningModels(requested);
    }

    public void validateReasoningModels(RuntimeSettingsService.AiConfig config) {
        if (apiKey.isEmpty()) {
            throw invalid("未設定 MISTRAL_API_KEY，無法驗證模型是否支援 reasoning=true");
        }
        LinkedHashSet<String> modelIds = new LinkedHashSet<>();
        config.models().values().forEach(route -> {
            modelIds.add(route.primary());
            modelIds.addAll(route.fallbacks());
        });
        modelIds.forEach(this::validateReasoningModel);
    }

    private void validateReasoningModel(String model) {
        Instant verifiedUntil = verifiedReasoningModels.get(model);
        if (verifiedUntil != null && verifiedUntil.isAfter(clock.instant())) return;
        try {
            String body = client.get().uri(uri -> uri.pathSegment("models", model).build())
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(String.class);
            JsonNode info = mapper.readTree(body);
            if (!info.path("capabilities").path("reasoning").asBoolean(false)) {
                throw invalid("模型 " + model + " 不支援 reasoning=true，無法儲存");
            }
            verifiedReasoningModels.put(model, clock.instant().plus(CACHE_TTL));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("驗證 Mistral 模型 reasoning 能力失敗：model={}, errorType={}",
                    model, exception.getClass().getSimpleName());
            throw invalid("無法驗證模型 " + model + " 的 reasoning 能力，請稍後再試");
        }
    }

    private CachedModels availableModels() {
        CachedModels current = cache;
        if (current != null && current.expiresAt().isAfter(clock.instant())) return current;
        CachedModels loaded = load();
        cache = loaded;
        return loaded;
    }

    private CachedModels load() {
        Instant expiresAt = clock.instant().plus(CACHE_TTL);
        if (apiKey.isEmpty()) {
            return new CachedModels(configuredOptions, false, "CONFIGURED_LIST",
                    "未設定 MISTRAL_API_KEY，模型尚未驗證 reasoning 能力，請先完成金鑰設定", expiresAt);
        }
        try {
            String body = client.get().uri("/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(String.class);
            List<String> ids = reasoningChatModelIds(mapper.readTree(body));
            if (ids.isEmpty()) throw new IllegalStateException("Mistral 回傳的模型清單為空");
            return new CachedModels(ids, true, "MISTRAL_API", null, expiresAt);
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("查詢 Mistral 模型清單失敗，改用設定清單：{}", exception.getClass().getSimpleName());
            // 失敗時只快取 1 分鐘，避免短暫斷線讓管理員十分鐘內都看不到真實清單
            return new CachedModels(configuredOptions, false, "CONFIGURED_LIST",
                    "無法連線 Mistral 驗證模型的 reasoning 能力，暫不允許選用未驗證模型",
                    clock.instant().plus(Duration.ofMinutes(1)));
        }
    }

    static List<String> reasoningChatModelIds(JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode model : data) {
            String id = model.path("id").asText("");
            if (id.isBlank() || model.path("deprecation").isTextual()) continue;
            JsonNode chat = model.path("capabilities").path("completion_chat");
            if (chat.isBoolean() && !chat.asBoolean()) continue;
            if (!model.path("capabilities").path("reasoning").asBoolean(false)) continue;
            ids.add(id);
        }
        return ids.stream().sorted().toList();
    }

    private static RestClient buildClient(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private record CachedModels(
            List<String> ids, boolean verified, String source, String warning, Instant expiresAt) {}

    /** 邏輯別名的中文名稱、用途與使用的 Agent（§6.7.2）。 */
    public record AliasInfo(String code, String label, String description, List<String> agents) {}

    /**
     * @param available 是否已由 Mistral 確認為可對話且支援 reasoning 的模型
     * @param inUse 是否為目前設定中的主模型或備援模型
     */
    public record ModelOption(String id, boolean available, boolean inUse) {}

    /**
     * @param source {@code MISTRAL_API}＝向 Mistral 即時查得；{@code CONFIGURED_LIST}＝取自 {@code mistral.model-options}
     * @param warning 清單非即時查得時的說明，前端直接顯示
     */
    public record AiConfigOptions(
            List<AliasInfo> aliases, List<ModelOption> models, String source, String warning) {}
}
