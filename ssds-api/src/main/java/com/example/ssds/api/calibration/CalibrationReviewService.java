package com.example.ssds.api.calibration;

import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.calibration.dto.ReviewCalibrationRequest;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.WeightVersionCommandService;
import com.example.ssds.api.weight.dto.CreateWeightVersionRequest;
import com.example.ssds.api.weight.dto.SceneGroupRequest;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.calibration.WeightAdjustments;
import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.WeightVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §FR-15 步驟 3／4：BUYER_LEAD 審核校準建議，核准或部分採納時產生權重版本草稿。
 *
 * <ul>
 * <li>AC-15-3：僅 BUYER_LEAD 可審核（§2.1 權限列 17）；只有核准／部分採納才產生新版本，駁回不動任何權重</li>
 * <li>AC-15-5：部分採納逐因子勾選，未勾選者等比例縮放（{@link WeightAdjustments}）</li>
 * <li>AC-15-6：新版本一律 DRAFT，須再走 FR-08 核准並指定生效日</li>
 * </ul>
 *
 * <p>新版本以報告產生當時的基準版本為底（{@code regression_result.scenes} 的 currentWeight 與
 * {@code baseVersionId} 的門檻），而非審核當下的現行版本：使用者審的是報告上的數字。
 *
 * <p>報告以悲觀鎖讀取：兩人同時審核同一份報告時，後到者會讀到已審核狀態而得到 409，
 * 不會建出兩個版本。
 */
@Service
@RequiredArgsConstructor
public class CalibrationReviewService {

    private static final String ENTITY_TYPE = "CalibrationReport";
    private static final Set<RoleCode> REVIEWERS = EnumSet.of(RoleCode.BUYER_LEAD);

    private final EntityManager entityManager;
    private final CalibrationReportService reportService;
    private final CalibrationDataLoader loader;
    private final CalibrationActors actors;
    private final WeightVersionCommandService weightVersionCommandService;
    private final WeightVersionRepository weightVersionRepository;

    @Transactional
    public CalibrationReportResponse review(Long id, ReviewCalibrationRequest request, String actorEmail, String ip) {
        AppUser actor = actors.require(actorEmail, REVIEWERS);
        CalibrationReport report = entityManager.find(CalibrationReport.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (report == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到校準報告 id=" + id);
        }
        if (report.getStatus() != CalibrationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "此校準報告已審核（" + report.getStatus() + "），不可重複審核");
        }

        JsonNode regression = read(report.getRegressionResult());
        boolean reject = request.action() == ReviewCalibrationRequest.Action.REJECT;
        if (!reject && (!regression.path("scenes").isArray() || !regression.hasNonNull("baseVersionId"))) {
            // dev seed（V903／V905）手寫的報告只有 factors，沒有四榜明細與基準版本，無法據以建版本
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "此報告缺少四榜權重明細（舊格式），請先重新產生 " + report.getQuarter() + " 報告再審核");
        }
        Set<FactorCode> changed = changedFactors(regression);
        Set<FactorCode> accepted = switch (request.action()) {
            case REJECT -> Set.of();
            case APPROVE -> {
                if (changed.isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "本次建議權重與現行版本相同，無需建立新版本，請改為駁回");
                }
                yield changed;
            }
            case PARTIAL -> validatePartial(request.acceptedFactors(), changed);
        };

        report.setReviewedBy(actor);
        report.setReviewedAt(Instant.now());
        Long versionId = null;
        if (reject) {
            report.setStatus(CalibrationStatus.REJECTED);
            report.setAcceptedItems(null);
        } else {
            report.setStatus(request.action() == ReviewCalibrationRequest.Action.APPROVE
                    ? CalibrationStatus.APPROVED : CalibrationStatus.PARTIAL);
            report.setAcceptedItems(write(names(accepted)));
            versionId = createDraft(report, regression, accepted, request, actor, ip);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", report.getStatus().name());
        after.put("acceptedFactors", names(accepted));
        after.put("weightVersionId", versionId);
        after.put("comment", request.comment());
        actors.audit(actor, "REVIEW", ENTITY_TYPE, id, "{\"status\":\"PENDING\"}", write(after), ip);
        return reportService.toResponse(report);
    }

    private static Set<FactorCode> validatePartial(List<FactorCode> requested, Set<FactorCode> changed) {
        if (requested == null || requested.stream().noneMatch(Objects::nonNull)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "部分採納須至少勾選一項調整");
        }
        Set<FactorCode> accepted = requested.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FactorCode.class)));
        Set<FactorCode> unchanged = accepted.stream().filter(code -> !changed.contains(code))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FactorCode.class)));
        if (!unchanged.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "以下因子沒有建議調整，不可勾選：" + unchanged);
        }
        return accepted;
    }

    private Long createDraft(CalibrationReport report, JsonNode regression, Set<FactorCode> accepted,
            ReviewCalibrationRequest request, AppUser actor, String ip) {
        long baseVersionId = regression.path("baseVersionId").asLong();
        Map<SceneType, GradeThreshold> thresholds = loader.thresholdRows(baseVersionId).stream()
                .collect(Collectors.toMap(GradeThreshold::getSceneType, t -> t));

        List<SceneGroupRequest> groups = new ArrayList<>();
        for (JsonNode scene : regression.path("scenes")) {
            SceneType sceneType = SceneType.valueOf(scene.path("sceneType").asText());
            Map<FactorCode, BigDecimal> current = new EnumMap<>(FactorCode.class);
            Map<FactorCode, BigDecimal> suggested = new EnumMap<>(FactorCode.class);
            for (JsonNode weight : scene.path("weights")) {
                FactorCode code = FactorCode.valueOf(weight.path("code").asText());
                current.put(code, weight.path("currentWeight").decimalValue());
                suggested.put(code, weight.path("suggestedWeight").decimalValue());
            }
            GradeThreshold threshold = thresholds.get(sceneType);
            if (threshold == null) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "基準版本缺少 " + sceneType + " 榜的分級門檻，無法建立新版本");
            }
            groups.add(new SceneGroupRequest(sceneType,
                    WeightAdjustments.applyAccepted(current, suggested, accepted),
                    threshold.getGradeAMin(), threshold.getGradeBMin()));
        }

        String mode = request.action() == ReviewCalibrationRequest.Action.APPROVE ? "核准" : "部分採納";
        String note = "由 " + report.getQuarter() + " 校準報告 #" + report.getId() + " " + mode + "產生（基準 "
                + regression.path("baseVersionNo").asText() + "；採納：" + names(accepted) + "）"
                + (request.comment() == null || request.comment().isBlank() ? "" : "。" + request.comment());
        // 同一季只有一份報告、一份報告只審核一次，系統本身不會撞號；
        // 若有人在 S-09 手動建了同名版本，FR-08 的重號檢查會回 409，整筆審核回滾
        WeightVersionDetailResponse created = weightVersionCommandService.create(new CreateWeightVersionRequest(
                report.getQuarter() + "-cal", report.getQuarter() + " 校準建議", truncate(note, 512), groups));

        WeightVersion version = weightVersionRepository.findById(created.id()).orElseThrow();
        version.setSourceCalibrationId(report.getId());
        version.setCreatedBy(actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("versionNo", version.getVersionNo());
        after.put("status", version.getStatus().name());
        after.put("sourceCalibrationId", report.getId());
        actors.audit(actor, "CREATE", "WeightVersion", version.getId(), null, write(after), ip);
        return version.getId();
    }

    /** 四榜任一榜建議 ≠ 現行的因子。 */
    static Set<FactorCode> changedFactors(JsonNode regression) {
        Set<FactorCode> changed = EnumSet.noneOf(FactorCode.class);
        for (JsonNode scene : regression.path("scenes")) {
            for (JsonNode weight : scene.path("weights")) {
                if (weight.path("currentWeight").decimalValue()
                        .compareTo(weight.path("suggestedWeight").decimalValue()) != 0) {
                    changed.add(FactorCode.valueOf(weight.path("code").asText()));
                }
            }
        }
        return changed;
    }

    private static List<String> names(Set<FactorCode> codes) {
        return codes.stream().map(Enum::name).toList();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static JsonNode read(String raw) {
        try {
            return CalibrationReportService.JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("regression_result 格式錯誤", e);
        }
    }

    private static String write(Object value) {
        try {
            return CalibrationReportService.JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("無法序列化", e);
        }
    }
}
