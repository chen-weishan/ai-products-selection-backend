package com.example.ssds.api.schedule;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FR-14-2 熱度來源健康檢查：<b>程式啟動完成後探測一次</b>（不再每 15 分鐘排程）。
 *
 * <p>與規格書的差異：規格書 §FR-14-2／AC-14-6／§5.10 寫「每 15 分鐘」，
 * 本專案改為「啟動時一次」以節省成本。之後要更新狀態靠
 * {@code POST /heat-sources/{id}/test}（手動測試連線）。
 *
 * <p>關閉方式：{@code ssds.heat-source-probe.on-startup=false}
 * （環境變數 {@code HEAT_SOURCE_PROBE_ON_STARTUP}）。關閉時這個 bean 整個不會被建立。
 *
 * <p>「連續 2 次失敗才 UNAVAILABLE」：既然一次啟動只有一輪，同一輪內第一次失敗後
 * 等 {@code retry-delay} 再探測一次，兩次都失敗才會落到 UNAVAILABLE；
 * 否則要連續重啟兩次才會判定，與規格書語意不符。
 *
 * <p>任何例外都不會往外拋，探測失敗不得影響程式啟動。
 */
@Component
@ConditionalOnProperty(name = "ssds.heat-source-probe.on-startup", havingValue = "true", matchIfMissing = true)
public class HeatSourceHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(HeatSourceHealthCheckJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /** §FR-14-2：人工標記來源改為檢查最近 30 日是否有標記。 */
    private static final int MANUAL_PROBE_LOOKBACK_DAYS = 30;

    /** 須與 HeatSource.UNAVAILABLE_AFTER_CONSECUTIVE_FAILURES 一致（規格書：連續 2 次）。 */
    private static final int MAX_ATTEMPTS = 2;

    private final HeatSourceRepository heatSourceRepository;
    private final ManualHeatTagRepository manualHeatTagRepository;
    private final AuditLogRepository auditLogRepository;
    private final Map<HeatSourceCode, HeatSourceAdapter> adaptersByCode;
    private final Duration retryDelay;

    public HeatSourceHealthCheckJob(
            HeatSourceRepository heatSourceRepository,
            ManualHeatTagRepository manualHeatTagRepository,
            AuditLogRepository auditLogRepository,
            List<HeatSourceAdapter> adapters,
            @Value("${ssds.heat-source-probe.retry-delay:3s}") Duration retryDelay) {
        this.heatSourceRepository = heatSourceRepository;
        this.manualHeatTagRepository = manualHeatTagRepository;
        this.auditLogRepository = auditLogRepository;
        this.adaptersByCode = adapters.stream()
                .collect(Collectors.toMap(HeatSourceAdapter::sourceCode, Function.identity()));
        this.retryDelay = retryDelay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        try {
            run();
        } catch (Exception e) {
            log.error("熱度來源啟動探測發生未預期錯誤，已略過，不影響程式運作。", e);
        }
    }

    void run() {
        List<HeatSource> sources = heatSourceRepository.findByEnabledTrue();
        if (sources.isEmpty()) {
            log.info("沒有 enabled 的熱度來源，略過啟動探測。");
            return;
        }

        LocalDate today = LocalDate.now(TAIPEI);
        int becameUnavailable = 0;

        for (HeatSource source : sources) {
            SourceAvailability before = source.getAvailability();

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                boolean success = probe(source.getSourceCode());
                source.applyProbeResult(success, today);
                if (success) {
                    break;
                }
                if (attempt < MAX_ATTEMPTS) {
                    sleep(retryDelay);
                }
            }
            heatSourceRepository.save(source);

            // §FR-14-2「由 AVAILABLE 轉為 UNAVAILABLE 時寫入 audit_log」。
            // 系統觸發，audit_log.user 為 null。
            if (before != SourceAvailability.UNAVAILABLE
                    && source.getAvailability() == SourceAvailability.UNAVAILABLE) {
                becameUnavailable++;
                auditLogRepository.save(AuditLog.builder()
                        .action("HEAT_SOURCE_UNAVAILABLE")
                        .entityType("HeatSource")
                        .entityId(source.getId())
                        .beforeJson("{\"availability\":\"" + before + "\"}")
                        .afterJson("{\"availability\":\"UNAVAILABLE\"}")
                        .build());
                log.warn("熱度來源 {} 轉為 UNAVAILABLE（連續探測失敗 {} 次）。",
                        source.getSourceCode(), source.getConsecutiveProbeFailures());
            }
        }

        log.info("熱度來源啟動探測完成：{} 個來源，{} 個新轉為 UNAVAILABLE。",
                sources.size(), becameUnavailable);
    }

    private boolean probe(HeatSourceCode sourceCode) {
        if (sourceCode == HeatSourceCode.MANUAL) {
            Instant since = Instant.now().minus(MANUAL_PROBE_LOOKBACK_DAYS, ChronoUnit.DAYS);
            return manualHeatTagRepository.existsByObservedAtAfter(since);
        }
        HeatSourceAdapter adapter = adaptersByCode.get(sourceCode);
        if (adapter == null) {
            log.warn("熱度來源 {} 沒有對應的 adapter，視為探測失敗。", sourceCode);
            return false;
        }
        return adapter.probe();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}