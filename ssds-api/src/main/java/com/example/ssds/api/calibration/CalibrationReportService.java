package com.example.ssds.api.calibration;

import com.example.ssds.api.admin.OperationalRuntimeConfigurable;
import com.example.ssds.api.admin.RuntimeSettingsService.OperationalConfig;
import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.calibration.Backtester;
import com.example.ssds.calibration.CalibrationSample;
import com.example.ssds.calibration.FactorStatistics;
import com.example.ssds.calibration.WeightScheme;
import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §FR-15 步驟 1：產生季度校準報告（統計迴歸＋回測），以及報告查詢。
 *
 * <p><b>{@code regression_result} JSON</b>
 * <pre>
 * method, label, sampleSize, minSample, minFactorSample, shrinkage, meanCorrelation,
 * baseVersionId, baseVersionNo, sampleFrom（樣本最早回填時間，無樣本為 null）, cutoff（樣本截止點）, generatedAt（實際產生時間；重算時更新，created_at 不會）, note,
 * factors[]    樣本充足的因子（Agent 7 輸入，欄位與 WeightCalibrationService 的 parser 對齊：其 requiredText／decimal 不接受 null，數值皆非 null）
 *              { code, correlation, pValue, n, sceneType, currentWeight, suggestedWeight }
 * factorRows[] 六因子全列（S-19 因子預測力表；樣本不足者 correlation／pValue 為 null）
 *              { code, n, correlation, pValue, sufficient, sceneType, currentWeight, suggestedWeight }
 * scenes[]     四榜完整明細，核准建版本時以此為準 { sceneType, weights[{ code, currentWeight, suggestedWeight }] }
 * </pre>
 * 因子列的 {@code sceneType} 取「現行權重最高的那一榜」作代表，對應示意圖 S-19「熱度斜率（話題爆款型）」的呈現。
 *
 * <p><b>{@code backtest_result} JSON</b>：{@code sampleSize, note,
 * backtests[]}（指標皆有定義者，Agent 7 輸入）與 {@code schemes[]}（平權／現行／建議全列，S-19 用）。
 * 兩者元素皆為 {@link Backtester.Outcome}，{@code backtests[]} 另以 {@code scheme} 欄位承載代碼。
 */
@Service
public class CalibrationReportService implements OperationalRuntimeConfigurable {

    static final String VALIDITY_WARNING = "樣本數不足，統計上建議累積至 %d 筆以上再進行權重調整。本次建議僅供參考，不建議直接核准。";

    /** 與 DecisionCommandService 相同自建：Spring Boot 4 只提供 Jackson 3 的 bean，沒有 com.fasterxml 的 ObjectMapper。 */
    static final ObjectMapper JSON = new ObjectMapper();

    /** §2.1 未列「產生報告」；設計決定比照權限列 17 再加系統管理員補跑。 */
    private static final Set<RoleCode> GENERATORS = EnumSet.of(RoleCode.BUYER_LEAD, RoleCode.SYS_ADMIN);

    private final CalibrationReportRepository reportRepository;
    private final CalibrationDataLoader loader;
    private final CalibrationActors actors;
    private final EntityManager entityManager;
    private volatile int minSample;
    /** 單一因子有效樣本低於此數視為「樣本不足」，建議權重不動。設計決定：預設 10，Spearman 在 n < 10 時幾乎不可能顯著。 */
    private final int minFactorSample;
    private final Clock clock;

    @Autowired
    public CalibrationReportService(
            CalibrationReportRepository reportRepository,
            CalibrationDataLoader loader,
            CalibrationActors actors,
            EntityManager entityManager,
            @Value("${ssds.calibration.min-sample:200}") int minSample,
            @Value("${ssds.calibration.min-factor-sample:10}") int minFactorSample) {
        this(reportRepository, loader, actors, entityManager, minSample, minFactorSample, Clock.systemUTC());
    }

    CalibrationReportService(
            CalibrationReportRepository reportRepository,
            CalibrationDataLoader loader,
            CalibrationActors actors,
            EntityManager entityManager,
            int minSample,
            int minFactorSample,
            Clock clock) {
        this.reportRepository = reportRepository;
        this.loader = loader;
        this.actors = actors;
        this.entityManager = entityManager;
        this.minSample = minSample;
        this.minFactorSample = minFactorSample;
        this.clock = clock;
    }

    @Override
    public void reconfigure(OperationalConfig config) {
        minSample = config.calibrationMinSample();
    }

    /**
     * 產生（或重算待審核的）季度報告。樣本截止點為季末，季度進行中則為現在。
     *
     * <p>已審核的報告不可重算：核准建立的權重版本依據的是當時的數字。
     * 重算會清空 AI 解讀——解讀的對象已經變了，舊解讀留著會對不上數字。
     *
     * <p>既有報告以悲觀鎖讀取，避免與審核同時進行時把已審核的報告蓋回 PENDING；
     * 首次建立撞到 {@code quarter} 唯一鍵（多台開發機同時跑季初排程）時回 409 而非 500。
     */
    @Transactional
    public CalibrationReportResponse generate(String quarterValue, String actorEmail, String ip) {
        var actor = actors.require(actorEmail, GENERATORS);
        CalibrationReportResponse response = generate(quarterValue);
        actors.audit(actor, "GENERATE", "CalibrationReport", response.id(), null,
                "{\"quarter\":\"" + response.quarter() + "\",\"sampleSize\":" + response.sampleSize() + "}", ip);
        return response;
    }

    /** 排程用（系統執行，無操作者）。 */
    @Transactional
    public CalibrationReportResponse generate(String quarterValue) {
        CalibrationQuarter quarter = CalibrationQuarter.parse(quarterValue);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, CalibrationQuarter.ZONE);
        if (quarter.firstDay().isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, quarter + " 尚未開始，無法產生校準報告");
        }
        CalibrationReport report = entityManager.createQuery(
                        "select r from CalibrationReport r where r.quarter = :quarter", CalibrationReport.class)
                .setParameter("quarter", quarter.toString())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst()
                .orElseGet(() -> CalibrationReport.builder().quarter(quarter.toString()).build());
        if (report.getId() != null && report.getStatus() != CalibrationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    quarter + " 校準報告已審核（" + report.getStatus() + "），不可重算");
        }

        Instant cutoff = now.isBefore(quarter.endExclusive()) ? now : quarter.endExclusive();
        WeightVersion current = loader.currentVersion();
        Map<SceneType, Map<FactorCode, BigDecimal>> currentWeights = CalibrationDataLoader.weightsOf(current);
        Map<SceneType, WeightScheme.GradeCut> thresholds = loader.thresholdsOf(current);
        List<CalibrationSample> samples = loader.samples(cutoff);

        FactorStatistics.Result regression =
                FactorStatistics.analyze(samples, currentWeights, minSample, minFactorSample);
        List<Backtester.Outcome> outcomes = List.of(
                Backtester.run(samples, WeightScheme.equalWeights("平權（六因子各 1/6）", thresholds)),
                Backtester.run(samples, loader.schemeOf("CURRENT", current)),
                Backtester.run(samples, new WeightScheme("SUGGESTED", "建議權重", null,
                        CalibrationDataLoader.toDouble(regression.suggestedWeights()), thresholds)));

        report.setSampleSize(samples.size());
        report.setRegressionResult(write(regressionJson(regression, current, loader.earliestFilledAt(cutoff), cutoff, now)));
        report.setBacktestResult(write(backtestJson(samples.size(), outcomes)));
        report.setStatus(CalibrationStatus.PENDING);
        report.setAiInterpretation(null);
        report.setAdjustmentAdvice("[]");
        report.setAttentionNotes("[]");
        report.setModel(null);
        report.setPromptVersion(null);
        report.setInterpretedAt(null);
        try {
            return toResponse(reportRepository.saveAndFlush(report));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    quarter + " 校準報告正由其他請求同時產生，請重新整理後再試");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<CalibrationReportResponse> list(Pageable pageable) {
        Pageable byQuarter = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "quarter"));
        Page<CalibrationReportResponse> page = reportRepository.findAll(byQuarter).map(this::toResponse);
        return PageResponse.from(page);
    }

    /** 尚未產生任何報告時回 null，畫面顯示空狀態。 */
    @Transactional(readOnly = true)
    public CalibrationReportResponse latest() {
        return reportRepository.findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "quarter")))
                .stream().findFirst().map(this::toResponse).orElse(null);
    }

    CalibrationReportResponse toResponse(CalibrationReport report) {
        boolean below = report.getSampleSize() < minSample;
        // 只在核准／部分採納後顯示：dev seed 的 v3 也指向 2026Q3 報告，但該報告仍待審核
        boolean reviewed = report.getStatus() == CalibrationStatus.APPROVED || report.getStatus() == CalibrationStatus.PARTIAL;
        WeightVersion created = reviewed ? createdVersion(report.getId()) : null;
        JsonNode regression = read(report.getRegressionResult());
        return new CalibrationReportResponse(
                report.getId(),
                report.getQuarter(),
                report.getSampleSize(),
                minSample,
                below,
                below ? VALIDITY_WARNING.formatted(minSample) : null,
                report.getStatus(),
                baseVersionStale(report, regression),
                regression == null ? null : JSON.convertValue(regression, Object.class),
                plain(report.getBacktestResult()),
                report.getAiInterpretation(),
                plain(report.getAdjustmentAdvice()),
                plain(report.getAttentionNotes()),
                report.getModel(),
                report.getInterpretedAt(),
                acceptedFactors(report),
                report.getReviewedBy() == null ? null : report.getReviewedBy().getDisplayName(),
                report.getReviewedAt(),
                created == null ? null : created.getId(),
                created == null ? null : created.getVersionNo(),
                report.getCreatedAt());
    }

    /**
     * 只對待審核報告判斷：已審核的報告不會再建版本；舊格式報告沒有 {@code baseVersionId}，本來就不能核准。
     * 沒有生效版本時也回 false——那時連重新產生都會失敗，提示重算沒有意義。
     */
    private boolean baseVersionStale(CalibrationReport report, JsonNode regression) {
        if (report.getStatus() != CalibrationStatus.PENDING || regression == null || !regression.hasNonNull("baseVersionId")) {
            return false;
        }
        long baseVersionId = regression.get("baseVersionId").asLong();
        return loader.currentVersionId().map(currentId -> currentId != baseVersionId).orElse(false);
    }

    /**
     * 由本報告產生的權重版本（{@code weight_version.source_calibration_id}）。
     * 直接下 JPQL 而不在 WeightVersionRepository 加方法：該檔另有進行中的分支改動，避免合併衝突。
     */
    private WeightVersion createdVersion(Long reportId) {
        return entityManager.createQuery(
                        "select v from WeightVersion v where v.sourceCalibrationId = :id order by v.id desc",
                        WeightVersion.class)
                .setParameter("id", reportId)
                .setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
    }

    private List<String> acceptedFactors(CalibrationReport report) {
        JsonNode node = read(report.getAcceptedItems());
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        node.forEach(item -> codes.add(item.asText()));
        return codes;
    }

    private ObjectNode regressionJson(
            FactorStatistics.Result result, WeightVersion base, Instant sampleFrom, Instant cutoff, Instant now) {
        ObjectNode root = JSON.createObjectNode();
        root.put("method", result.method());
        root.put("label", "campaign_result.actual_qty");
        root.put("sampleSize", result.sampleSize());
        root.put("minSample", result.minSample());
        root.put("minFactorSample", result.minFactorSample());
        root.put("shrinkage", round(result.shrinkage()));
        root.put("meanCorrelation", round(result.meanCorrelation()));
        root.put("baseVersionId", base.getId());
        root.put("baseVersionNo", base.getVersionNo());
        // 樣本是累積的：記下最早回填時間，S-19 顯示樣本期間，日後才判斷得了是否改為滑動視窗
        root.put("sampleFrom", sampleFrom == null ? null : sampleFrom.toString());
        root.put("cutoff", cutoff.toString());
        root.put("generatedAt", now.toString());

        ArrayNode factors = root.putArray("factors");
        ArrayNode rows = root.putArray("factorRows");
        List<String> insufficient = new ArrayList<>();
        for (FactorStatistics.FactorResult factor : result.factors()) {
            SceneType scene = representativeScene(result.currentWeights(), factor.code());
            BigDecimal current = result.currentWeights().get(scene).get(factor.code());
            BigDecimal suggested = result.suggestedWeights().get(scene).get(factor.code());
            ObjectNode row = rows.addObject();
            row.put("code", factor.code().name());
            row.put("n", factor.n());
            putNullable(row, "correlation", factor.correlation());
            putNullable(row, "pValue", factor.pValue());
            row.put("sufficient", factor.sufficient());
            row.put("sceneType", scene.name());
            row.put("currentWeight", current);
            row.put("suggestedWeight", suggested);
            if (factor.sufficient()) {
                ObjectNode item = factors.addObject();
                item.put("code", factor.code().name());
                item.put("correlation", round(factor.correlation()));
                item.put("pValue", round(factor.pValue()));
                item.put("n", factor.n());
                item.put("sceneType", scene.name());
                item.put("currentWeight", current);
                item.put("suggestedWeight", suggested);
            } else {
                insufficient.add(factor.code().name() + "（" + factor.n() + " 筆）");
            }
        }

        ArrayNode scenes = root.putArray("scenes");
        for (SceneType scene : SceneType.values()) {
            ObjectNode sceneNode = scenes.addObject();
            sceneNode.put("sceneType", scene.name());
            ArrayNode weights = sceneNode.putArray("weights");
            for (FactorCode code : FactorStatistics.BONUS_FACTORS) {
                ObjectNode weight = weights.addObject();
                weight.put("code", code.name());
                weight.put("currentWeight", result.currentWeights().get(scene).get(code));
                weight.put("suggestedWeight", result.suggestedWeights().get(scene).get(code));
            }
        }

        StringBuilder note = new StringBuilder("以 Spearman 等級相關衡量各因子與實際銷量的關聯；建議權重 = 現行權重 × (1 + α·(r − r̄)) 後各榜正規化，α = min(1, 樣本數 / ")
                .append(result.minSample()).append(") = ").append(round(result.shrinkage())).append("。");
        if (!insufficient.isEmpty()) {
            note.append("樣本不足（< ").append(result.minFactorSample()).append(" 筆）而維持現行權重的因子：")
                    .append(String.join("、", insufficient)).append("。");
        }
        root.put("note", note.toString());
        return root;
    }

    private ObjectNode backtestJson(int sampleSize, List<Backtester.Outcome> outcomes) {
        ObjectNode root = JSON.createObjectNode();
        root.put("sampleSize", sampleSize);
        ArrayNode complete = root.putArray("backtests");
        ArrayNode all = root.putArray("schemes");
        for (Backtester.Outcome outcome : outcomes) {
            ObjectNode node = JSON.valueToTree(outcome);
            putNullable(node, "correlation", outcome.correlation());
            putNullable(node, "pearson", outcome.pearson());
            putNullable(node, "gradeAHitRate", outcome.gradeAHitRate());
            all.add(node);
            if (outcome.correlation() != null && outcome.gradeAHitRate() != null) {
                ObjectNode item = node.deepCopy();
                item.put("scheme", outcome.code());
                item.put("correlation", round(outcome.correlation()));
                item.put("gradeAHitRate", round(outcome.gradeAHitRate()));
                complete.add(item);
            }
        }
        String missing = outcomes.stream()
                .filter(o -> o.correlation() == null || o.gradeAHitRate() == null)
                .map(Backtester.Outcome::label)
                .collect(Collectors.joining("、"));
        root.put("note", "以同一批 " + sampleSize + " 筆已回填樣本重算分數比較；A 級達標率 = 重算後 A 級中如期或提前售罄的比例。"
                + (missing.isEmpty() ? "" : missing + " 因樣本不足或無 A 級品項，指標無定義。"));
        return root;
    }

    /** 該因子現行權重最高的榜；同分取 SceneType 宣告順序。 */
    private static SceneType representativeScene(Map<SceneType, Map<FactorCode, BigDecimal>> weights, FactorCode code) {
        return weights.entrySet().stream()
                .max(Comparator.<Map.Entry<SceneType, Map<FactorCode, BigDecimal>>, BigDecimal>comparing(
                                e -> e.getValue().getOrDefault(code, BigDecimal.ZERO))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private static void putNullable(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, round(value));
        }
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** 回應走 Spring 的 Jackson 3，Jackson 2 的 JsonNode 不能直接放進 DTO，轉成 Map／List。 */
    private static Object plain(String raw) {
        JsonNode node = read(raw);
        return node == null ? null : JSON.convertValue(node, Object.class);
    }

    private static JsonNode read(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("calibration_report JSON 欄位格式錯誤", e);
        }
    }

    private String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("無法序列化校準結果", e);
        }
    }
}
