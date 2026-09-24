// package com.example.ssds.api.schedule;

// import com.example.ssds.core.domain.HeatSourceCode;
// import com.example.ssds.core.domain.SourceAvailability;
// import com.example.ssds.infra.entity.AuditLog;
// import com.example.ssds.infra.entity.HeatSource;
// import com.example.ssds.infra.repository.AuditLogRepository;
// import com.example.ssds.infra.repository.HeatSourceRepository;
// import com.example.ssds.infra.repository.ManualHeatTagRepository;
// import com.example.ssds.ingest.HeatSourceAdapter;
// import java.time.Instant;
// import java.time.LocalDate;
// import java.time.ZoneId;
// import java.time.temporal.ChronoUnit;
// import java.util.List;
// import java.util.Map;
// import java.util.function.Function;
// import java.util.stream.Collectors;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
// import org.springframework.scheduling.annotation.Scheduled;
// import org.springframework.stereotype.Component;
// import org.springframework.transaction.annotation.Transactional;

// /**
//  * FR-14-2「探測排程」：每 15 分鐘對每個 enabled 的熱度來源探測一次，寫回
//  * {@code availability}／{@code last_probed_at}／{@code quota_used}。
//  *
//  * <p>不像規格書列出的排程用了 ShedLock（「經 ShedLock 保證單節點執行」）——
//  * 本專案目前沒有 ShedLock 依賴，其他既有排程（ThreadsHeatIngestJob 等）也都沒有用，
//  * 沿用既有慣例先不加；多節點部署時這支排程可能被重複執行，屬於已知風險，
//  * 不是本次補實作的範圍。
//  *
//  * <p><b>2026-09-23 暫停：</b>{@code probe()} 的預設實作是真的打一次 {@code fetch()}，
//  * 不是輕量探測，每 15 分鐘跑一次會持續消耗 Apify 額度。FR-14 這塊功能先暫停，
//  * 用 {@code @ConditionalOnProperty} 讓這個 bean（連同它的 {@code @Scheduled}）
//  * 整個不被 Spring 建立，不是只是把 cron 改很長——這樣可以保證絕對不會被漏改
//  * 或誤觸發。之後要重新啟用，把
//  * {@code ssds.heat-source-probe.enabled=true} 設回去即可，不用改程式碼。
//  * 同一個開關也擋掉了「測試連線」的手動探測，見 {@code HeatSourceCommandService}。
//  */
// @Component
// @ConditionalOnProperty(name = "ssds.heat-source-probe.enabled", havingValue = "true")
// public class HeatSourceHealthCheckJob {

//     private static final Logger log = LoggerFactory.getLogger(HeatSourceHealthCheckJob.class);
//     private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

//     /** §FR-14-2：人工標記來源改為檢查最近 30 日是否有標記。 */
//     private static final int MANUAL_PROBE_LOOKBACK_DAYS = 30;

//     private final HeatSourceRepository heatSourceRepository;
//     private final ManualHeatTagRepository manualHeatTagRepository;
//     private final AuditLogRepository auditLogRepository;
//     private final Map<HeatSourceCode, HeatSourceAdapter> adaptersByCode;

//     public HeatSourceHealthCheckJob(
//             HeatSourceRepository heatSourceRepository,
//             ManualHeatTagRepository manualHeatTagRepository,
//             AuditLogRepository auditLogRepository,
//             List<HeatSourceAdapter> adapters) {
//         this.heatSourceRepository = heatSourceRepository;
//         this.manualHeatTagRepository = manualHeatTagRepository;
//         this.auditLogRepository = auditLogRepository;
//         this.adaptersByCode = adapters.stream()
//                 .collect(Collectors.toMap(HeatSourceAdapter::sourceCode, Function.identity()));
//     }

//     @Scheduled(cron = "${ssds.schedule.heat-source-health-check.cron:0 */15 * * * *}", zone = "Asia/Taipei")
//     @Transactional
//     public void run() {
//         List<HeatSource> sources = heatSourceRepository.findByEnabledTrue();
//         if (sources.isEmpty()) {
//             log.debug("沒有 enabled 的熱度來源，略過健康檢查。");
//             return;
//         }

//         LocalDate today = LocalDate.now(TAIPEI);
//         int probed = 0;
//         int becameUnavailable = 0;

//         for (HeatSource source : sources) {
//             SourceAvailability before = source.getAvailability();
//             boolean success = probe(source.getSourceCode());
//             source.applyProbeResult(success, today);
//             heatSourceRepository.save(source);
//             probed++;

//             // §FR-14-2「狀態變更：由 AVAILABLE 轉為 UNAVAILABLE 時寫入 audit_log 並於
//             // AI 任務中心顯示提示」——本排程是系統排程觸發，非使用者操作，audit_log.user 為 null
//             // （見 AuditLog entity 類別註解：系統排程觸發的變更為 null）。
//             if (before != SourceAvailability.UNAVAILABLE && source.getAvailability() == SourceAvailability.UNAVAILABLE) {
//                 becameUnavailable++;
//                 auditLogRepository.save(AuditLog.builder()
//                         .action("HEAT_SOURCE_UNAVAILABLE")
//                         .entityType("HeatSource")
//                         .entityId(source.getId())
//                         .beforeJson("{\"availability\":\"" + before + "\"}")
//                         .afterJson("{\"availability\":\"UNAVAILABLE\"}")
//                         .build());
//                 log.warn("熱度來源 {} 轉為 UNAVAILABLE（連續探測失敗 {} 次）。",
//                         source.getSourceCode(), source.getConsecutiveProbeFailures());
//             }
//         }

//         log.info("熱度來源健康檢查完成：探測 {} 個來源，{} 個新轉為 UNAVAILABLE。", probed, becameUnavailable);
//     }

//     private boolean probe(HeatSourceCode sourceCode) {
//         if (sourceCode == HeatSourceCode.MANUAL) {
//             Instant since = Instant.now().minus(MANUAL_PROBE_LOOKBACK_DAYS, ChronoUnit.DAYS);
//             return manualHeatTagRepository.existsByObservedAtAfter(since);
//         }
//         HeatSourceAdapter adapter = adaptersByCode.get(sourceCode);
//         if (adapter == null) {
//             log.warn("熱度來源 {} 沒有對應的 adapter，本輪探測視為失敗。", sourceCode);
//             return false;
//         }
//         return adapter.probe();
//     }
// }