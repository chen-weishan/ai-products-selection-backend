package com.example.ssds.api.risk;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.repository.RiskAlertRepository;

/** S-11 頂部四張 KPI 卡與「最後偵測」時間。 */
@Service
public class RiskAlertSummaryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    private final RiskAlertRepository alerts;

    public RiskAlertSummaryService(RiskAlertRepository alerts) {
        this.alerts = alerts;
    }

    /**
     * @param highOpen 高　未處理
     * @param handledThisMonth 本月（台北時間）已確認或忽略的示警數
     * @param lastDetectedAt 全部示警中最近一次的偵測時間；尚無任何示警時為 null
     */
    public record Summary(
            long highOpen,
            long mediumOpen,
            long lowOpen,
            long handledThisMonth,
            OffsetDateTime lastDetectedAt) {}

    @Transactional(readOnly = true)
    public Summary summary() {
        return summary(Instant.now());
    }

    Summary summary(Instant now) {
        Map<Severity, Long> open = new EnumMap<>(Severity.class);
        for (Object[] row : alerts.countOpenGroupedBySeverity()) {
            open.put((Severity) row[0], ((Number) row[1]).longValue());
        }
        Instant monthStart = YearMonth.from(now.atZone(ZONE)).atDay(1).atStartOfDay(ZONE).toInstant();
        Instant last = alerts.findLastDetectedAt();
        return new Summary(
                open.getOrDefault(Severity.HIGH, 0L),
                open.getOrDefault(Severity.MEDIUM, 0L),
                open.getOrDefault(Severity.LOW, 0L),
                alerts.countHandledSince(monthStart),
                last == null ? null : last.atZone(ZONE).toOffsetDateTime());
    }
}
