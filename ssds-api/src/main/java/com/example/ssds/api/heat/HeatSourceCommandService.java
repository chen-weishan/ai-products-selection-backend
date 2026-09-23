package com.example.ssds.api.heat;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.heat.dto.HeatSourceDetailResponse;
import com.example.ssds.api.heat.dto.HeatSourceTestResponse;
import com.example.ssds.api.heat.dto.HeatSourceUpdateRequest;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-16 熱度來源管理的異動操作（規格書 FR-14-2）：啟用／停用、調整合成權重、測試連線。
 *
 * <p>合成權重的異動時機刻意只更新 {@code heat_source.composite_weight} 本身，
 * <b>不</b>在這裡觸發重新評分——規格書明講「於下一次每日熱度合成（次日 06:00）生效，
 * 並觸發該次合成後的全量重新評分」，也就是由每日排程讀到新權重後才算數，
 * 不是 API 呼叫當下立即生效。這裡只負責「寫入新權重＋留下 audit_log」。
 */
@Service
@RequiredArgsConstructor
public class HeatSourceCommandService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /** §FR-14-2：人工標記來源改為檢查「最近 30 日是否有標記」。 */
    private static final int MANUAL_PROBE_LOOKBACK_DAYS = 30;

    private final HeatSourceRepository heatSourceRepository;
    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository appUserRepository;
    private final ManualHeatTagRepository manualHeatTagRepository;
    private final List<HeatSourceAdapter> adapters;

    /**
     * AC-14-5：僅 SYS_ADMIN 可調整合成權重（由 controller 的 {@code @PreAuthorize} 把關，
     * 這裡是資料異動本身）。{@link HeatSourceUpdateRequest} 兩欄皆為選填，null 表不異動。
     */
    @Transactional
    public HeatSourceDetailResponse update(Long id, HeatSourceUpdateRequest request) {
        HeatSource source = heatSourceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到熱度來源 id=" + id));

        String beforeJson = String.format(
                "{\"enabled\":%s,\"compositeWeight\":%s}", source.isEnabled(), source.getCompositeWeight());

        if (request.enabled() != null) {
            source.setEnabled(request.enabled());
        }
        if (request.compositeWeight() != null) {
            source.setCompositeWeight(request.compositeWeight());
        }
        heatSourceRepository.save(source);

        String afterJson = String.format(
                "{\"enabled\":%s,\"compositeWeight\":%s}", source.isEnabled(), source.getCompositeWeight());

        auditLogRepository.save(AuditLog.builder()
                .user(currentUser())
                .action("UPDATE")
                .entityType("HeatSource")
                .entityId(source.getId())
                .beforeJson(beforeJson)
                .afterJson(afterJson)
                .build());

        return HeatSourceMapper.toDetail(source);
    }

    /**
     * S-16「測試連線」：立即探測一次並回饋結果，不等下一輪 15 分鐘排程。
     * 與排程共用同一套 {@link HeatSource#applyProbeResult} 判定邏輯，避免兩處邏輯分岔。
     */
    @Transactional
    public HeatSourceTestResponse testConnection(Long id) {
        HeatSource source = heatSourceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到熱度來源 id=" + id));

        boolean success = probe(source.getSourceCode());
        source.applyProbeResult(success, LocalDate.now(TAIPEI));
        heatSourceRepository.save(source);

        String message = success ? "探測成功" : "探測失敗，請確認連線設定或稍後再試";
        return new HeatSourceTestResponse(
                source.getSourceCode().name(), success, source.getAvailability().name(), message);
    }

    /**
     * MANUAL 來源沒有對應的 {@link HeatSourceAdapter}（人工標記走資料庫、不走外部 API），
     * 改用「最近 30 日是否有標記」判定（§FR-14-2 表格）；其餘來源交給對應 adapter 的
     * {@link HeatSourceAdapter#probe()}。
     */
    private boolean probe(HeatSourceCode sourceCode) {
        if (sourceCode == HeatSourceCode.MANUAL) {
            Instant since = Instant.now().minus(MANUAL_PROBE_LOOKBACK_DAYS, ChronoUnit.DAYS);
            return manualHeatTagRepository.existsByObservedAtAfter(since);
        }
        Map<HeatSourceCode, HeatSourceAdapter> byCode = adapters.stream()
                .collect(java.util.stream.Collectors.toMap(HeatSourceAdapter::sourceCode, Function.identity()));
        HeatSourceAdapter adapter = byCode.get(sourceCode);
        if (adapter == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "來源 " + sourceCode + " 沒有對應的 adapter，無法測試連線");
        }
        return adapter.probe();
    }

    /** 比照 {@code ManualHeatTagCommandService}：取用 JwtAuthenticationFilter 設定的登入者 id。 */
    private AppUser currentUser() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return appUserRepository.getReferenceById(userId);
    }
}
