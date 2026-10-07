package com.example.ssds.api.risk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.ssds.api.scoring.PureScoringBatchService;

/** 門檻異動後執行無 LLM 全量重評；併跑期間的新異動會排入下一輪。 */
@Service
public class RiskRuleRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleRecalculationService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final PureScoringBatchService scoring;
    private final RiskHeatAlertService heatAlerts;
    private final RiskSeasonAlertService seasonAlerts;
    private final RiskFestivalAlertService festivalAlerts;
    private long generation;
    private boolean running;
    private int completed;
    private int total;
    private Instant startedAt;
    private Instant finishedAt;
    private String lastError;

    public RiskRuleRecalculationService(
            PureScoringBatchService scoring,
            RiskHeatAlertService heatAlerts,
            RiskSeasonAlertService seasonAlerts,
            RiskFestivalAlertService festivalAlerts) {
        this.scoring = scoring;
        this.heatAlerts = heatAlerts;
        this.seasonAlerts = seasonAlerts;
        this.festivalAlerts = festivalAlerts;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRulesChanged(RiskRulesChangedEvent ignored) {
        synchronized (this) {
            generation++;
            if (running) {
                return;
            }
            running = true;
            startedAt = Instant.now();
            finishedAt = null;
            lastError = null;
        }
        runLatestGeneration();
    }

    private void runLatestGeneration() {
        while (true) {
            final long currentGeneration;
            synchronized (this) {
                currentGeneration = generation;
                completed = 0;
                total = 0;
                // 每一輪重新計錯：上一輪的失敗已被這一輪的結果取代，否則成功的輪次仍會帶著舊錯誤
                lastError = null;
            }
            Instant detectedAt = Instant.now();
            LocalDate today = LocalDate.now(TAIPEI);
            try {
                scoring.evaluateAll(detectedAt, this::updateProgress);
            } catch (RuntimeException exception) {
                log.error("風險門檻變更後的全量重評失敗", exception);
                synchronized (this) {
                    lastError = exception.getClass().getSimpleName();
                }
            }
            detectSafely("熱度", () -> heatAlerts.detect(today, detectedAt));
            detectSafely("季節不匹配", () -> seasonAlerts.detect(detectedAt));
            detectSafely("檔期窗", () -> festivalAlerts.detect(today, detectedAt));
            synchronized (this) {
                if (generation != currentGeneration) {
                    continue;
                }
                running = false;
                finishedAt = Instant.now();
                if (total == 0) {
                    completed = 0;
                }
                return;
            }
        }
    }

    private void detectSafely(String label, Runnable detection) {
        try {
            detection.run();
        } catch (RuntimeException exception) {
            log.error("風險門檻變更後的{}示警補掃失敗", label, exception);
            synchronized (this) {
                lastError = (lastError == null ? "" : lastError + "; ") + label + ": " + exception.getClass().getSimpleName();
            }
        }
    }

    private synchronized void updateProgress(int done, int count) {
        completed = done;
        total = count;
    }

    public synchronized Snapshot snapshot() {
        int percent = total == 0 ? (running ? 0 : 100) : (int) Math.floor(completed * 100.0 / total);
        return new Snapshot(running, completed, total, percent,
                taipei(startedAt), taipei(finishedAt), lastError);
    }

    private static OffsetDateTime taipei(Instant value) {
        return value == null ? null : value.atZone(TAIPEI).toOffsetDateTime();
    }

    public record Snapshot(
            boolean running,
            int completed,
            int total,
            int progressPercent,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String lastError) {}
}
