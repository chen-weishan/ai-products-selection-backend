package com.example.ssds.api.schedule;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.ManualHeatTagCalculator;
import com.example.ssds.core.domain.ManualHeatTagCalculator.Observation;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.MANUAL).orElse(null);
        if (source == null) {
            log.warn("heat_source 尚未註冊 MANUAL 這筆，略過人工標記寫入。");
            return;
        }
        if (!source.isEnabled()) {
            log.info("MANUAL 來源已停用（enabled=false），略過寫入。");
            return;
        }

        LocalDate today = LocalDate.now(TAIPEI);
        Instant evaluationInstant = Instant.now();
        Instant since = evaluationInstant.minus(expireDays, ChronoUnit.DAYS);

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

        int written = 0;
        int skippedExpired = 0;
        for (Map.Entry<Long, List<Observation>> entry : observationsByKeyword.entrySet()) {
            Optional<java.math.BigDecimal> rawValue = ManualHeatTagCalculator.computeRawValue(
                    entry.getValue(), evaluationInstant, halveAfterDays, expireDays);

            if (rawValue.isEmpty()) {
                // 該關鍵字（含展開進來的品項觀測）全部已失效（age ≥ expireDays）：
                // 衰減已歸零，不再寫入——這正是步驟 5「向前填補直到衰減歸零為止」的終點。
                skippedExpired++;
                continue;
            }

            upsert(source, entry.getKey(), today, rawValue.get());
            written++;
        }

        source.setLastFetchedAt(Instant.now());
        heatSourceRepository.save(source);

        log.info(
                "人工熱度標記寫入完成：{} 個關鍵字有效、{} 個關鍵字已全數衰減歸零略過、{} 個品項因未綁定關鍵字而無法映射。",
                written, skippedExpired, unmappableProducts);
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