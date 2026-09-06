package com.example.ssds.api.weight;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.dto.ApproveWeightVersionRequest;
import com.example.ssds.api.weight.dto.CreateWeightVersionRequest;
import com.example.ssds.api.weight.dto.SceneGroupRequest;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WeightVersionCommandService {

    private final WeightVersionRepository weightVersionRepository;
    private final GradeThresholdRepository gradeThresholdRepository;
    private final AppUserRepository appUserRepository;

    /**
     * FR-01 之前的暫時核准人：seed 的 lead@ssds.dev（BUYER_LEAD，AC-08-3 指定的角色）。
     * ck_weight_version_approved 要求 status=APPROVED 時 approved_by 不可為 null，
     * 本 Phase 沒有登入機制，只能先頂一個。FR-01 完成後改為當前登入者並刪除本常數。
     */
    private static final Long TEMP_APPROVER_ID = 2L;

    /** 分數值域上限（§5.5 加分小計上限 100；DB 端為 ck_grade_threshold_range）。 */
    private static final BigDecimal GRADE_MAX = new BigDecimal("100");

    @Transactional
    public WeightVersionDetailResponse create(CreateWeightVersionRequest request) {

        boolean hasVersionNo = weightVersionRepository.findByVersionNo(request.versionNo()).isPresent();
        if (hasVersionNo)
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "版本號 " + request.versionNo() + " 已被其他版本使用");

        validateRequest(request);

        WeightVersion newVersion = new WeightVersion();
        WeightVersion version = applyRequest(newVersion, request);

        List<GradeThreshold> thresholds = applySceneGroups(version, request);

        weightVersionRepository.save(version);
        gradeThresholdRepository.saveAll(thresholds);

        return WeightVersionMapper.toDetail(version, thresholds);
    }

    @Transactional
    public WeightVersionDetailResponse update(Long id, CreateWeightVersionRequest request) {
        WeightVersion oldVersion = weightVersionRepository.findWithProfilesById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到權重版本 id=" + id));
        if (!oldVersion.isEditable())
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "只有草稿（DRAFT）可以編輯，此版本目前為 " + oldVersion.getStatus());
        WeightVersion hasVersionNo = weightVersionRepository.findByVersionNo(request.versionNo()).orElse(null);
        if (hasVersionNo != null && !hasVersionNo.getId().equals(id))
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "版本號 " + request.versionNo() + " 已被其他版本使用");
        validateRequest(request);

        WeightVersion version = applyRequest(oldVersion, request);
        version.getProfiles().clear();
        weightVersionRepository.flush(); // 先把 DELETE 送出，否則下面的 INSERT 會撞 uk_weight_profile

        List<GradeThreshold> thresholds = applySceneGroups(version, request);
        gradeThresholdRepository.saveAll(thresholds);
        return WeightVersionMapper.toDetail(version, thresholds);
    }

    @Transactional
    public WeightVersionDetailResponse approve(Long id, ApproveWeightVersionRequest request) {
        WeightVersion version = weightVersionRepository.findWithProfilesById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到權重版本 id=" + id));
        if (version.getStatus() != WeightVersionStatus.DRAFT)
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "只有草稿（DRAFT）可以核准，此版本目前為 " + version.getStatus());

        for (SceneType scene : SceneType.values()) {
            BigDecimal sum = version.getProfiles().stream().filter(p -> p.getSceneType() == scene)
                    .map(WeightProfile::getWeight).reduce(BigDecimal.ZERO,
                            BigDecimal::add);
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessException(ErrorCode.WEIGHT_SUM_INVALID,
                        scene + " 榜權重加總為 " + sum + "，必須等於 1.000，無法核准");
            }
        }

        // 「退役舊版 → 設新版為 current」必須是不可分割的一步。
        // 悲觀鎖讓兩個同時 approve 的交易排隊，後到的那個會讀到已被退役的舊版。
        WeightVersion currentVersion = weightVersionRepository.findCurrentForUpdate().orElse(null);
        if (currentVersion != null) {
            currentVersion.setCurrent(false);
            currentVersion.setStatus(WeightVersionStatus.RETIRED);
            weightVersionRepository.flush();
        }
        version.setStatus(WeightVersionStatus.APPROVED);
        version.setCurrent(true);
        version.setEffectiveFrom(request.effectiveFrom());
        version.setApprovedAt(Instant.now());
        // TODO FR-01 後改為 SecurityContextHolder 取得的當前登入者
        version.setApprovedBy(appUserRepository.getReferenceById(TEMP_APPROVER_ID));
        // TODO Phase 2：觸發全量重新評分（§FR-08 版本管理表「生效切換」）

        // 目前一筆 current 都沒有時，上面的鎖沒有列可鎖，兩個交易會同時通過。
        // 真正的守門員是 partial unique index uk_weight_version_current；
        // 這裡主動 flush 把 INSERT／UPDATE 送出，讓違反在方法內被接住轉成 409，
        // 而不是等交易提交時才炸、落到兜底 handler 變成 500。
        try {
            weightVersionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "另一個版本正在同時核准生效，請重新整理後再試");
        }

        List<GradeThreshold> thresholds = gradeThresholdRepository.findByVersionId(id);
        return WeightVersionMapper.toDetail(version, thresholds);
    }

    private void validateRequest(CreateWeightVersionRequest request) {
        Set<SceneType> given = request.sceneGroups().stream()
                .map(SceneGroupRequest::sceneType)
                .collect(Collectors.toSet());
        Set<SceneType> expected = EnumSet.allOf(SceneType.class);
        if (!given.equals(expected)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "必須提供四榜的設定，缺少：" + expected.stream().filter(s -> !given.contains(s)).toList());
        }

        Set<FactorCode> expectedFactors = Arrays.stream(FactorCode.values())
                .filter(f -> !f.isPenalty())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FactorCode.class)));
        for (SceneGroupRequest group : request.sceneGroups()) {
            Set<FactorCode> givenFactors = group.weights().keySet();
            if (!givenFactors.equals(expectedFactors)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        group.sceneType() + "榜的因子不正確，應為" + expectedFactors);
            }

            // 先檢查單值域再檢查加總：負權重可以被另一個超過 1 的權重抵銷掉，
            // 只驗加總會讓 (-0.5, 1.5) 這種組合通過，最後撞 DB 的
            // weight_profile_weight_check（weight >= 0 AND weight <= 1）變成 500。
            // 約束名是 V1 的 inline CHECK 由 PostgreSQL 自動命名，
            // 與 ck_weight_profile_factor_code／ck_weight_profile_scene 是不同的三條。
            for (Map.Entry<FactorCode, BigDecimal> entry : group.weights().entrySet()) {
                BigDecimal weight = entry.getValue();
                if (weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            group.sceneType() + " 榜的 " + entry.getKey() + " 權重為 " + weight
                                    + "，必須介於 0.000 與 1.000 之間");
                }
            }

            BigDecimal sum = group.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessException(ErrorCode.WEIGHT_SUM_INVALID,
                        group.sceneType() + " 榜權重加總為 " + sum + "，必須等於 1.000");
            }

            // 分數值域 0–100（§5.5 加分小計上限 100，DB 端為 ck_grade_threshold_range）。
            validateGradeRange(group.sceneType(), "A 級門檻", group.gradeAMin());
            validateGradeRange(group.sceneType(), "B 級門檻", group.gradeBMin());

            if (group.gradeAMin().compareTo(group.gradeBMin()) <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        group.sceneType() + " 榜的 A 級門檻（" + group.gradeAMin()
                                + "）必須大於 B 級門檻（" + group.gradeBMin() + "）");
            }
        }
    }

    private void validateGradeRange(SceneType scene, String label, BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(GRADE_MAX) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    scene + " 榜的 " + label + "為 " + value + "，必須介於 0 與 100 之間");
        }
    }

    /**
     * 把四榜設定套進版本：建立權重明細，並回傳四筆門檻。
     *
     * <p>create 與 update 共用同一份邏輯，差別只在門檻是新建還是沿用既有列——
     * 由 {@code version.getId()} 是否為 null 判斷，呼叫端不需要再傳旗標。
     * 兩邊各寫一份迴圈時已經 drift 過一次（create 直接 new、update 做 upsert），
     * 之後改驗證規則只改一處。
     */
    private List<GradeThreshold> applySceneGroups(WeightVersion version, CreateWeightVersionRequest request) {
        List<GradeThreshold> thresholds = new ArrayList<>();
        for (SceneGroupRequest group : request.sceneGroups()) {
            version.getProfiles().addAll(buildProfiles(version, group));
            thresholds.add(upsertThreshold(version, group));
        }
        return thresholds;
    }

    private List<WeightProfile> buildProfiles(WeightVersion version, SceneGroupRequest group) {
        List<WeightProfile> profiles = new ArrayList<>();
        for (Map.Entry<FactorCode, BigDecimal> entry : group.weights().entrySet()) {
            WeightProfile profile = new WeightProfile();
            profile.setVersion(version);
            profile.setSceneType(group.sceneType());
            profile.setFactorCode(entry.getKey());
            profile.setWeight(entry.getValue());
            profiles.add(profile);
        }
        return profiles;
    }

    private GradeThreshold upsertThreshold(WeightVersion version, SceneGroupRequest group) {
        GradeThreshold threshold = version.getId() == null
                ? new GradeThreshold()
                : gradeThresholdRepository
                        .findByVersionIdAndSceneType(version.getId(), group.sceneType())
                        .orElseGet(GradeThreshold::new);
        threshold.setVersion(version);
        threshold.setSceneType(group.sceneType());
        threshold.setGradeAMin(group.gradeAMin());
        threshold.setGradeBMin(group.gradeBMin());
        return threshold;
    }

    private WeightVersion applyRequest(WeightVersion version, CreateWeightVersionRequest request) {
        version.setVersionNo(request.versionNo());
        version.setName(request.name());
        version.setStatus(WeightVersionStatus.DRAFT);
        version.setChangeNote(request.changeNote());
        // TODO FR-01 完成後改為當前登入者（SecurityContextHolder）
        // version.setCreatedBy(currentUser);
        return version;
    }

}
