package com.example.ssds.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * <p>模型清單優先向 Mistral {@code GET /models} 查帳號實際可用的對話模型（快取 10 分鐘）；
 * 未設金鑰或查詢失敗時退回 {@code mistral.model-options} 設定清單。目前設定中的模型一律併入，
 * 避免已在用的模型因不在清單內而無法在下拉中顯示。
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
                .map(id -> new ModelOption(id, models.ids().contains(id), inUse.contains(id)))
                .toList();
        return new AiConfigOptions(ALIASES, options, models.source(), models.warning());
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
            return new CachedModels(configuredOptions, "CONFIGURED_LIST",
                    "未設定 MISTRAL_API_KEY，清單取自系統設定，未向 Mistral 確認可用性", expiresAt);
        }
        try {
            String body = client.get().uri("/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(String.class);
            List<String> ids = chatModelIds(mapper.readTree(body));
            if (ids.isEmpty()) throw new IllegalStateException("Mistral 回傳的模型清單為空");
            return new CachedModels(ids, "MISTRAL_API", null, expiresAt);
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("查詢 Mistral 模型清單失敗，改用設定清單：{}", exception.getClass().getSimpleName());
            // 失敗時只快取 1 分鐘，避免短暫斷線讓管理員十分鐘內都看不到真實清單
            return new CachedModels(configuredOptions, "CONFIGURED_LIST",
                    "無法連線 Mistral 查詢可用模型，清單取自系統設定", clock.instant().plus(Duration.ofMinutes(1)));
        }
    }

    static List<String> chatModelIds(JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode model : data) {
            String id = model.path("id").asText("");
            if (id.isBlank() || model.path("deprecation").isTextual()) continue;
            JsonNode chat = model.path("capabilities").path("completion_chat");
            if (chat.isBoolean() && !chat.asBoolean()) continue;
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

    private record CachedModels(List<String> ids, String source, String warning, Instant expiresAt) {}

    /** 邏輯別名的中文名稱、用途與使用的 Agent（§6.7.2）。 */
    public record AliasInfo(String code, String label, String description, List<String> agents) {}

    /**
     * @param available 是否出現在可用清單（Mistral 查詢結果或設定清單）
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
