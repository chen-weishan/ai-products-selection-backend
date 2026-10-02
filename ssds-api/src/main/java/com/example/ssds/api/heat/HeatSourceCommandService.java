package com.example.ssds.api.heat;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.heat.dto.HeatSourceDetailResponse;
import com.example.ssds.api.heat.dto.HeatSourceTestResponse;
import com.example.ssds.api.heat.dto.HeatSourceUpdateRequest;
import com.example.ssds.api.security.CurrentUserId;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-16 熱度來源管理的異動操作（規格書 FR-14-2）：啟用／停用、調整合成權重、測試連線。
 *
 * <p>AC-14-5：合成權重<b>實際變動</b>時，本服務在交易內寫入新值與 audit_log 後發布
 * {@link HeatSourceCompositionChangedEvent}；交易提交後由
 * {@link HeatCompositionRecalculationListener} 非同步重算當日 {@code heat_composite_daily}
 * 並執行全量重新評分，不等次日 06:00 排程。事件在 AFTER_COMMIT 才處理，
 * 因為合成 SQL 直接讀 {@code heat_source.composite_weight}，必須讀到已提交的新值。
 * 值沒有變的 PUT（例如 0.3 送成 0.300）不會發布事件，避免無意義的全量重評。
 *
 * <p>啟用狀態（enabled）只控制排程是否採集，不影響合成，所以切換 enabled 只寫
 * audit_log、不發布事件，也就不會觸發全量重評。
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
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 探測總開關（2026-09-23 為節省成本暫停 FR-14 探測時加入）。現在 Apify 來源的探測只驗證
     * token 與讀取用量、不消耗爬取額度，但開關保留，設為 false 時「測試連線」直接短路。
     */
    @Value("${ssds.heat-source-probe.enabled:true}")
    private boolean probeEnabled;

    /**
     * AC-14-5：僅 SYS_ADMIN 可調整合成權重（由 controller 的 {@code @PreAuthorize} 把關，
     * 這裡是資料異動本身）。{@link HeatSourceUpdateRequest} 兩欄皆為選填，null 表不異動。
     * 合成權重有實際變動時，於交易提交後觸發合成重算與全量重評分；enabled 只控制後續採集。
     */
    @Transactional
    public HeatSourceDetailResponse update(Long id, HeatSourceUpdateRequest request) {
        HeatSource source = heatSourceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到熱度來源 id=" + id));

        String beforeJson = String.format(
                "{\"enabled\":%s,\"compositeWeight\":%s}", source.isEnabled(), source.getCompositeWeight());

        boolean weightChanged = request.compositeWeight() != null
                && source.getCompositeWeight().compareTo(request.compositeWeight()) != 0;
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

        if (weightChanged) {
            eventPublisher.publishEvent(
                    new HeatSourceCompositionChangedEvent(source.getId(), source.getSourceCode()));
        }

        return HeatSourceMapper.toDetail(source);
    }

    /**
     * S-16「測試連線」：立即檢查一次並回饋結果（目前沒有自動排程，狀態靠這個按鈕與人工標記更新）。
     *
     * <p>Apify 來源只驗證 token 與連線（{@code GET /v2/users/me}），並順便把 Apify 帳號本月用量
     * 寫入 {@code quota_used／quota_limit}；兩者都不執行 actor、不消耗爬取額度。
     * 判定邏輯與其他探測共用 {@link HeatSource#applyProbeResult}，避免兩處邏輯分岔。
     */
    @Transactional
    public HeatSourceTestResponse testConnection(Long id) {
        HeatSource source = heatSourceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到熱度來源 id=" + id));

        if (!probeEnabled) {
            // 暫停期間直接短路，不呼叫 probe()、不動 heat_source 任何欄位。
            return new HeatSourceTestResponse(
                    source.getSourceCode().name(),
                    false,
                    source.getAvailability().name(),
                    "熱度來源探測功能目前暫停中（額度控管，FR-14-2），未實際發送探測請求");
        }

        boolean success = probe(source.getSourceCode());
        if (success && source.getSourceCode() != HeatSourceCode.MANUAL) {
            // token 驗證通過才讀 Apify 本月用量，且先寫入再判定狀態，
            // 讓「額度 ≥80% 降級、100% 不可用」用的是剛讀到的數字，而不是上次殘留的值。
            HeatSourceQuota.refresh(source, adapterFor(source.getSourceCode()));
        }
        source.applyProbeResult(success, LocalDate.now(TAIPEI));
        heatSourceRepository.save(source);

        String message = success ? "探測成功" : "探測失敗，請確認連線設定或稍後再試";
        return new HeatSourceTestResponse(
                source.getSourceCode().name(), success, source.getAvailability().name(), message);
    }

    /**
     * MANUAL 來源沒有對應的 {@link HeatSourceAdapter}（人工標記走資料庫、不走外部 API），
     * 改用「最近 30 日是否有標記」判定（§FR-14-2 表格）；其餘來源交給對應 adapter 的
     * {@link HeatSourceAdapter#probe()}（Apify 來源為輕量 token 驗證）。
     */
    private boolean probe(HeatSourceCode sourceCode) {
        if (sourceCode == HeatSourceCode.MANUAL) {
            Instant since = Instant.now().minus(MANUAL_PROBE_LOOKBACK_DAYS, ChronoUnit.DAYS);
            return manualHeatTagRepository.existsByObservedAtAfter(since);
        }
        return adapterFor(sourceCode).probe();
    }

    private HeatSourceAdapter adapterFor(HeatSourceCode sourceCode) {
        Map<HeatSourceCode, HeatSourceAdapter> byCode = adapters.stream()
                .collect(java.util.stream.Collectors.toMap(HeatSourceAdapter::sourceCode, Function.identity()));
        HeatSourceAdapter adapter = byCode.get(sourceCode);
        if (adapter == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "來源 " + sourceCode + " 沒有對應的 adapter，無法測試連線");
        }
        return adapter;
    }

    /** 比照 {@code ManualHeatTagCommandService}：取用目前登入者 id（見 {@link CurrentUserId}）。 */
    private AppUser currentUser() {
        return appUserRepository.getReferenceById(CurrentUserId.require());
    }
}
