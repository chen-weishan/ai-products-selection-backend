package com.example.ssds.api.heat;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-14-2：送出人工熱度標記後，順便更新 MANUAL 來源的健康狀態。
 *
 * <p>專案已把「每 15 分鐘健康檢查」改成「啟動時一次」，MANUAL 的狀態因此不會隨標記更新。
 * 這裡改成每送出一筆標記就探測一次，判定邏輯與健康檢查、「測試連線」完全相同：
 * 「最近 30 日是否有標記」為探測成功條件，結果交給 {@link HeatSource#applyProbeResult}
 * （連續失敗、資料落後、額度用量的判定都在那裡，不在此重複）。
 *
 * <p>MANUAL 只查資料庫，不呼叫外部 API、不耗額度，所以不受
 * {@code ssds.heat-source-probe.enabled} 的暫停開關影響。
 *
 * <p>AFTER_COMMIT 階段沿用的交易已結束，寫入必須開新交易；任何例外只記錄、不外拋，
 * 避免標記已存檔卻因為狀態更新失敗而讓 API 回 500。
 */
@Component
public class ManualSourceHealthListener {

    private static final Logger log = LoggerFactory.getLogger(ManualSourceHealthListener.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /** 須與 {@code HeatSourceHealthCheckJob} 的 MANUAL 探測窗口一致。 */
    private static final int MANUAL_PROBE_LOOKBACK_DAYS = 30;

    private final HeatSourceRepository heatSourceRepository;
    private final ManualHeatTagRepository manualHeatTagRepository;
    private final TransactionTemplate newTransaction;

    public ManualSourceHealthListener(
            HeatSourceRepository heatSourceRepository,
            ManualHeatTagRepository manualHeatTagRepository,
            PlatformTransactionManager transactionManager) {
        this.heatSourceRepository = heatSourceRepository;
        this.manualHeatTagRepository = manualHeatTagRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * {@code @Order(1)}：必須先於 {@link ManualHeatReadingSyncListener}（Order 2）執行。
     * 同步 listener 會立刻重算合成，合成 SQL 只採 {@code availability <> 'UNAVAILABLE'} 的來源；
     * 若 MANUAL 還停在「最近 30 日無標記 → UNAVAILABLE」，第一筆標記的讀值會被排除在合成之外。
     */
    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagSubmitted(ManualHeatTagSubmittedEvent event) {
        try {
            newTransaction.executeWithoutResult(status -> refreshManualSource(event.tagId(), true));
        } catch (RuntimeException e) {
            log.error("送出人工標記 id={} 後更新 MANUAL 來源狀態失敗", event.tagId(), e);
        }
    }

    /** 刪除標記後重新探測：只重判「最近 30 日是否還有標記」，不動 lastFetchedAt（刪除不是新資料）。 */
    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagDeleted(ManualHeatTagDeletedEvent event) {
        try {
            newTransaction.executeWithoutResult(status -> refreshManualSource(event.tagId(), false));
        } catch (RuntimeException e) {
            log.error("刪除人工標記 id={} 後更新 MANUAL 來源狀態失敗", event.tagId(), e);
        }
    }

    private void refreshManualSource(Long tagId, boolean submitted) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.MANUAL).orElse(null);
        if (source == null) {
            log.warn("heat_source 尚未註冊 MANUAL 這筆，略過狀態更新。");
            return;
        }
        Instant since = Instant.now().minus(MANUAL_PROBE_LOOKBACK_DAYS, ChronoUnit.DAYS);
        boolean success = manualHeatTagRepository.existsByObservedAtAfter(since);

        // 只有「當日（Asia/Taipei）觀察到的標記」才刷新 lastFetchedAt；補登昨日以前的標記不算，
        // 資料落後（> 2 日）的降級判定因此不會被舊標記洗掉。
        boolean taggedToday = submitted && manualHeatTagRepository.findById(tagId)
                .map(tag -> tag.getObservedAt().atZone(TAIPEI).toLocalDate().equals(LocalDate.now(TAIPEI)))
                .orElse(false);
        if (success && taggedToday) {
            source.setLastFetchedAt(Instant.now());
        }

        source.applyProbeResult(success, LocalDate.now(TAIPEI));
        heatSourceRepository.save(source);
        log.info("{}人工標記 id={} 後 MANUAL 來源探測{}，狀態 {}，連續失敗 {} 次",
                submitted ? "送出" : "刪除", tagId, success ? "成功" : "失敗", source.getAvailability(), source.getConsecutiveProbeFailures());
    }
}