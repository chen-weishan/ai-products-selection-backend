package com.example.ssds.api.heat;

import com.example.ssds.api.schedule.ManualHeatReadingJob;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-14-1 步驟 5 的即時版：送出／編輯／刪除人工標記後，不等每日 03:45 排程，
 * 立刻把受影響關鍵字今天的 MANUAL 讀值寫進 {@code heat_reading}，並重算百分位與當日合成。
 *
 * <p>流程（任何一步失敗只記 log、不外拋，標記本身已存檔，不能因此讓 API 回 500）：
 * <ol>
 *   <li>{@link ManualHeatReadingJob#syncForTarget}：重算受影響關鍵字今天的 raw_value（含時間衰減與信心係數）</li>
 *   <li>{@link HeatReadingPercentileDao#applyPercentiles}：百分位是「同來源、同一天」跨關鍵字排名，
 *       一個關鍵字的讀值變動會讓其他關鍵字的名次跟著動，所以要整天重算</li>
 *   <li>重算合成：受影響關鍵字，加上今天有 MANUAL 讀值的其他關鍵字（它們的百分位剛剛可能變了）</li>
 * </ol>
 *
 * <p>與每日排程並存：排程仍會跑，結果相同（冪等 upsert），當作漏網與跨日衰減的保底。
 * 這裡刻意不觸發全量重評分（品項分數），那是 AC-14-5 權重變動才需要的重工作業。
 */
@Component
public class ManualHeatReadingSyncListener {

    private static final Logger log = LoggerFactory.getLogger(ManualHeatReadingSyncListener.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ManualHeatReadingJob readingJob;
    private final ManualHeatTagRepository manualHeatTagRepository;
    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final HeatReadingPercentileDao percentileDao;
    private final HeatCompositeCalibrationService calibrationService;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;

    public ManualHeatReadingSyncListener(
            ManualHeatReadingJob readingJob,
            ManualHeatTagRepository manualHeatTagRepository,
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            HeatReadingPercentileDao percentileDao,
            HeatCompositeCalibrationService calibrationService,
            PlatformTransactionManager transactionManager) {
        this.readingJob = readingJob;
        this.manualHeatTagRepository = manualHeatTagRepository;
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.percentileDao = percentileDao;
        this.calibrationService = calibrationService;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        // AFTER_COMMIT 階段原交易雖已提交，資源仍綁在執行緒上；REQUIRED 的寫入會「加入」已結束的交易而不被提交。
        // 因此百分位與合成的寫入一律明確開新交易（比照 ManualSourceHealthListener）。
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Order(2)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagSubmitted(ManualHeatTagSubmittedEvent event) {
        syncByTagId(event.tagId(), "送出");
    }

    @Order(2)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagUpdated(ManualHeatTagUpdatedEvent event) {
        syncByTagId(event.tagId(), "編輯");
    }

    @Order(2)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTagDeleted(ManualHeatTagDeletedEvent event) {
        sync(event.keywordId(), event.productId(), "刪除 id=" + event.tagId());
    }

    private void syncByTagId(Long tagId, String action) {
        try {
            Optional<ManualHeatTag> tag = manualHeatTagRepository.findWithDetailsById(tagId);
            if (tag.isEmpty()) {
                return;
            }
            Long keywordId = tag.get().getKeyword() != null ? tag.get().getKeyword().getId() : null;
            Long productId = tag.get().getProduct() != null ? tag.get().getProduct().getId() : null;
            sync(keywordId, productId, action + " id=" + tagId);
        } catch (RuntimeException e) {
            log.error("人工標記{} id={} 後即時同步熱度讀值失敗", action, tagId, e);
        }
    }

    private void sync(Long keywordId, Long productId, String reason) {
        try {
            LocalDate today = LocalDate.now(TAIPEI);
            Set<Long> affected = readingJob.syncForTarget(keywordId, productId, today, Instant.now());
            if (affected.isEmpty()) {
                return;
            }

            writeTx.executeWithoutResult(status -> percentileDao.applyPercentiles(today));

            Set<Long> toCompose = new LinkedHashSet<>(affected);
            toCompose.addAll(keywordIdsWithManualReading(today));

            int composed = 0;
            int failed = 0;
            for (Long id : toCompose) {
                try {
                    Boolean present = writeTx.execute(status -> calibrationService.computeAndPersist(id, today).isPresent());
                    if (Boolean.TRUE.equals(present)) {
                        composed++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("人工標記{}後，關鍵字 id={} 重算合成失敗，跳過。", reason, id, e);
                }
            }
            log.info("人工標記{}後即時同步完成：受影響關鍵字 {}，重算合成 {} 個、失敗 {} 個。",
                    reason, affected, composed, failed);
        } catch (RuntimeException e) {
            log.error("人工標記{}後即時同步熱度讀值失敗", reason, e);
        }
    }

    /** 今天已有 MANUAL 讀值的關鍵字；在唯讀交易內取 id，避免離開交易後碰 lazy 關聯。 */
    private Set<Long> keywordIdsWithManualReading(LocalDate today) {
        Optional<HeatSource> source = heatSourceRepository.findBySourceCode(HeatSourceCode.MANUAL);
        if (source.isEmpty()) {
            return Set.of();
        }
        Long sourceId = source.get().getId();
        Set<Long> ids = readOnlyTx.execute(status -> heatReadingRepository
                .findBySourceIdAndReadingDate(sourceId, today).stream()
                .map(reading -> reading.getKeyword())
                .filter(Objects::nonNull)
                .map(TrendKeyword::getId)
                .collect(Collectors.toSet()));
        return ids != null ? ids : Set.of();
    }
}