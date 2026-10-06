package com.example.ssds.api.schedule;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.ManualHeatTagCalculator;
import com.example.ssds.core.domain.ManualHeatTagCalculator.Observation;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-14-1 步驟 5：每日把人工標記換算後的 raw_value 寫入 {@code heat_reading}
 * （{@code source_code = 'MANUAL'}），與其他來源一致；「稀疏期向前填補」不需要
 * 額外邏輯，見 {@link ManualHeatTagCalculator} 類別註解。
 *
 * <p><b>品項（A 軌）標記的映射規則（原本完全未實作，見程式碼歷史 review）：</b>
 * {@code heat_reading} 只有 {@code keyword_id}／{@code category_id} 兩個目標維度
 * （{@code ck_heat_reading_target}），沒有 {@code product_id}。這裡採用的規則是：
 * <b>把綁在品項上的標記，展開計入該品項當下每一個關聯關鍵字（{@code product_keyword}）
 * 的觀測清單</b>，與該關鍵字原本就有的、直接綁在關鍵字上的標記合併後一起算衰減／
 * 信心係數，而不是另外開一個獨立的分數維度。
 *
 * <p>選這個規則而非替 {@code heat_reading} 新增 {@code product_id} 欄位，主要理由：
 * <ol>
 *   <li>{@link Product} 本身的實體註解已經寫明「一個品項可綁多個關鍵字，熱度取合成值」——
 *       品項熱度本來就是由其關鍵字的合成值決定，而不是獨立算一份，所以「品項標記
 *       ＝該品項所有關鍵字都收到一份觀測」與既有架構一致，不需要新的分數維度。</li>
 *   <li>不動 schema、不用補 migration，風險與影響範圍都小很多。</li>
 * </ol>
 * 副作用（刻意接受、非 bug）：品項同時綁定多個關鍵字時，同一則標記會分別計入每個
 * 關鍵字各自的觀測清單——這不是「重複計入同一個分數」，而是「這則觀測對品項綁定的
 * 每個主題都成立」，語意上合理，但仍是一個未經規格書作者背書的解讀，
 * 在 §FR-14-1／§7.2 明確補上這條映射規則之前，這裡的決定僅供先解除「品項標記完全
 * 不生效」這個更明顯的落差，之後若規格書給出不同規則（例如改成新增 product_id
 * 維度），本方法需要重寫。
 *
 * <p>沒有綁定任何關鍵字的品項標記，目前仍然沒有地方可以映射，會被跳過並記警告
 * （見 {@link #run()} 內的 {@code unmappableProducts} 計數）。
 */
@Component
public class ManualHeatReadingJob {

    private static final Logger log = LoggerFactory.getLogger(ManualHeatReadingJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ManualHeatTagRepository manualHeatTagRepository;
    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final TrendKeywordRepository trendKeywordRepository;
    private final ProductRepository productRepository;
    private final int halveAfterDays;
    private final int expireDays;

    public ManualHeatReadingJob(
            ManualHeatTagRepository manualHeatTagRepository,
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            TrendKeywordRepository trendKeywordRepository,
            ProductRepository productRepository,
            @Value("${ssds.heat-tag.halve-after-days:14}") int halveAfterDays,
            @Value("${ssds.heat-tag.expire-days:30}") int expireDays) {
        this.manualHeatTagRepository = manualHeatTagRepository;
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.trendKeywordRepository = trendKeywordRepository;
        this.productRepository = productRepository;
        this.halveAfterDays = halveAfterDays;
        this.expireDays = expireDays;
    }

    @Scheduled(cron = "${ssds.schedule.manual-heat.cron:0 45 3 * * *}", zone = "Asia/Taipei")
    @Transactional
    public void run() {
        reconcile(LocalDate.now(TAIPEI), Instant.now());
    }

    /**
     * 將指定營業日的 MANUAL reading 與目前仍有效的人工標記完整對帳。
     *
     * <p>人工 reading 是可重建的衍生資料：標記失效、刪除或品項關鍵字映射改變時，
     * 不只要 upsert 仍有效的值，也要刪除當日已不該存在的舊 reading。外部來源的
     * reading 是既有觀測事實，不適用這項刪除規則。
     */
    @Transactional
    public ReconcileResult reconcile(LocalDate businessDate, Instant evaluationInstant) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.MANUAL).orElse(null);
        if (source == null) {
            log.warn("heat_source 尚未註冊 MANUAL 這筆，略過人工標記寫入。");
            return ReconcileResult.noChange();
        }
        if (!source.isEnabled()) {
            log.info("MANUAL 來源已停用（enabled=false），略過寫入。");
            return ReconcileResult.noChange();
        }

        Instant since = evaluationInstant.minus(expireDays, ChronoUnit.DAYS);

        Collected collected = collectObservations(since);
        Map<Long, List<Observation>> observationsByKeyword = collected.byKeyword();
        int unmappableProducts = collected.unmappableProducts();

        Map<Long, BigDecimal> expectedValues = new LinkedHashMap<>();
        int skippedExpired = 0;
        for (Map.Entry<Long, List<Observation>> entry : observationsByKeyword.entrySet()) {
            Optional<BigDecimal> rawValue = ManualHeatTagCalculator.computeRawValue(
                    entry.getValue(), evaluationInstant, halveAfterDays, expireDays);
            if (rawValue.isPresent()) {
                expectedValues.put(entry.getKey(), rawValue.get());
            } else {
                // 該關鍵字（含展開進來的品項觀測）全部已失效（age ≥ expireDays）：
                // 衰減已歸零，不再寫入——這正是步驟 5「向前填補直到衰減歸零為止」的終點。
                skippedExpired++;
            }
        }

        Map<Long, HeatReading> existingByKeyword = new LinkedHashMap<>();
        for (HeatReading reading : heatReadingRepository.findBySourceIdAndReadingDate(source.getId(), businessDate)) {
            if (reading.getKeyword() != null) {
                existingByKeyword.put(reading.getKeyword().getId(), reading);
            }
        }

        Set<Long> affectedKeywordIds = new LinkedHashSet<>();
        for (Map.Entry<Long, BigDecimal> entry : expectedValues.entrySet()) {
            Long keywordId = entry.getKey();
            BigDecimal rawValue = entry.getValue();
            HeatReading reading = existingByKeyword.remove(keywordId);
            if (reading == null) {
                reading = HeatReading.builder()
                        .source(source)
                        .keyword(trendKeywordRepository.getReferenceById(keywordId))
                        .readingDate(businessDate)
                        .rawValue(rawValue)
                        .build();
                heatReadingRepository.save(reading);
                affectedKeywordIds.add(keywordId);
            } else if (reading.getRawValue().compareTo(rawValue) != 0) {
                reading.setRawValue(rawValue);
                heatReadingRepository.save(reading);
                affectedKeywordIds.add(keywordId);
            } else if (reading.getPercentileWithinSource() == null) {
                // reading 已存在但尚未進過百分位批次，六點後啟動時仍必須觸發合成。
                affectedKeywordIds.add(keywordId);
            }
        }

        for (Map.Entry<Long, HeatReading> stale : existingByKeyword.entrySet()) {
            heatReadingRepository.delete(stale.getValue());
            affectedKeywordIds.add(stale.getKey());
        }

        SourceAvailability availabilityBefore = source.getAvailability();
        if (!expectedValues.isEmpty()) {
            source.setLastFetchedAt(evaluationInstant);
        }
        source.applyIngestResult(!expectedValues.isEmpty(), businessDate);
        heatSourceRepository.save(source);
        boolean availabilityChanged = availabilityBefore != source.getAvailability();

        log.info(
                "人工熱度 reading 對帳完成：日期 {}，{} 個關鍵字有效、{} 個關鍵字資料異動、{} 個關鍵字已全數衰減歸零略過、{} 個品項因未綁定關鍵字而無法映射。",
                businessDate, expectedValues.size(), affectedKeywordIds.size(), skippedExpired, unmappableProducts);
        return new ReconcileResult(
                !affectedKeywordIds.isEmpty(), availabilityChanged, Set.copyOf(affectedKeywordIds));
    }

    public record ReconcileResult(
            boolean readingsChanged,
            boolean availabilityChanged,
            Set<Long> affectedKeywordIds) {

        public static ReconcileResult noChange() {
            return new ReconcileResult(false, false, Set.of());
        }

        public boolean requiresRecomposition() {
            return readingsChanged || availabilityChanged;
        }
    }

    /** 一次收齊「最近 expireDays 天內仍可能有效」的觀測，依關鍵字分組（品項標記已展開成其關聯關鍵字）。 */
    private Collected collectObservations(Instant since) {
        Map<Long, List<Observation>> observationsByKeyword = new LinkedHashMap<>();

        // 直接綁在關鍵字上的標記（原本就有的路徑）。
        for (Long keywordId : manualHeatTagRepository.findDistinctKeywordIdsByObservedAtAfter(since)) {
            List<ManualHeatTag> tags = manualHeatTagRepository.findByKeywordIdOrderByObservedAtDesc(keywordId);
            observationsByKeyword
                    .computeIfAbsent(keywordId, id -> new ArrayList<>())
                    .addAll(toObservations(tags));
        }

        // 綁在品項上的標記：展開計入該品項當下每個關聯關鍵字（見類別註解的映射規則）。
        int unmappableProducts = 0;
        for (Long productId : manualHeatTagRepository.findDistinctProductIdsByObservedAtAfter(since)) {
            Product product = productRepository.findWithDetailsById(productId).orElse(null);
            if (product == null) {
                log.warn("品項 id={} 有人工標記但品項本身已找不到，略過。", productId);
                continue;
            }
            List<TrendKeyword> linkedKeywords = product.getKeywords().stream().toList();
            if (linkedKeywords.isEmpty()) {
                unmappableProducts++;
                log.warn("品項 id={} 尚未綁定任何關鍵字，其人工標記暫時無法映射到 heat_reading，已跳過。", productId);
                continue;
            }
            List<Observation> productObservations =
                    toObservations(manualHeatTagRepository.findByProductIdOrderByObservedAtDesc(productId));
            for (TrendKeyword keyword : linkedKeywords) {
                observationsByKeyword
                        .computeIfAbsent(keyword.getId(), id -> new ArrayList<>())
                        .addAll(productObservations);
            }
        }

        return new Collected(observationsByKeyword, unmappableProducts);
    }

    private record Collected(Map<Long, List<Observation>> byKeyword, int unmappableProducts) {}

    /**
     * 送出／編輯／刪除人工標記後的即時同步：只重算受影響關鍵字「今天」的 MANUAL 讀值。
     *
     * <p>計算邏輯與每日排程完全共用（{@link #collectObservations}＋{@link ManualHeatTagCalculator}），
     * 差別只在範圍：這裡只處理 {@code keywordId}，或 {@code productId} 展開後的關聯關鍵字。
     * 受影響關鍵字若已沒有任何有效標記（例如剛刪掉最後一筆），今天的讀值會被移除，
     * 否則畫面會一直吃到已不存在的標記。
     *
     * <p>用 REQUIRES_NEW：呼叫端是 AFTER_COMMIT 階段，原交易已結束，寫入必須開新交易。
     *
     * @return 受影響的關鍵字 id；來源未註冊／已停用／找不到對應關鍵字時為空集合
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<Long> syncForTarget(Long keywordId, Long productId, LocalDate today, Instant evaluationInstant) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.MANUAL).orElse(null);
        if (source == null || !source.isEnabled()) {
            return Set.of();
        }

        Set<Long> targets = new LinkedHashSet<>();
        if (keywordId != null) {
            targets.add(keywordId);
        }
        if (productId != null) {
            productRepository.findWithDetailsById(productId)
                    .ifPresent(product -> product.getKeywords().forEach(k -> targets.add(k.getId())));
        }
        if (targets.isEmpty()) {
            log.warn("人工標記即時同步：keywordId={}、productId={} 找不到可映射的關鍵字，略過。", keywordId, productId);
            return Set.of();
        }

        Collected collected = collectObservations(evaluationInstant.minus(expireDays, ChronoUnit.DAYS));
        for (Long targetId : targets) {
            List<Observation> observations = collected.byKeyword().getOrDefault(targetId, List.of());
            Optional<java.math.BigDecimal> rawValue = observations.isEmpty()
                    ? Optional.empty()
                    : ManualHeatTagCalculator.computeRawValue(observations, evaluationInstant, halveAfterDays, expireDays);
            if (rawValue.isPresent()) {
                upsert(source, targetId, today, rawValue.get());
            } else {
                heatReadingRepository
                        .findByKeywordIdAndSourceIdAndReadingDate(targetId, source.getId(), today)
                        .ifPresent(heatReadingRepository::delete);
            }
        }
        log.info("人工標記即時同步完成：關鍵字 {}。", targets);
        return targets;
    }

    private List<Observation> toObservations(List<ManualHeatTag> tags) {
        return tags.stream()
                .map(t -> new Observation(t.getHeatLevel(), t.getObservedAt(),
                        t.getTaggedBy() != null ? t.getTaggedBy().getId() : null))
                .toList();
    }

    private void upsert(HeatSource source, Long keywordId, LocalDate date, java.math.BigDecimal rawValue) {
        HeatReading reading = heatReadingRepository
                .findByKeywordIdAndSourceIdAndReadingDate(keywordId, source.getId(), date)
                .orElseGet(() -> HeatReading.builder()
                        .source(source)
                        // 比照 WeightVersionCommandService 既有慣例：只需要 FK，用
                        // getReferenceById 拿代理物件即可，不必多打一次 SELECT。
                        .keyword(trendKeywordRepository.getReferenceById(keywordId))
                        .readingDate(date)
                        .build());
        reading.setRawValue(rawValue);
        heatReadingRepository.save(reading);
    }
}
