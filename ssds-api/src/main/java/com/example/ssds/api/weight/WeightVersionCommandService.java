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

    @Transactional
    public WeightVersionDetailResponse create(CreateWeightVersionRequest request) {

        boolean hasVersionNo = weightVersionRepository.findByVersionNo(request.versionNo()).isPresent();
        if (hasVersionNo)
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "版本號 " + request.versionNo() + " 已被其他版本使用");

        validateRequest(request);

        WeightVersion newVersion = new WeightVersion();
        WeightVersion version = applyRequest(newVersion, request);

        List<GradeThreshold> thresholds = new ArrayList<>();

        for (SceneGroupRequest group : request.sceneGroups()) {
            for (Map.Entry<FactorCode, BigDecimal> entry : group.weights().entrySet()) {
                WeightProfile profile = new WeightProfile();
                profile.setVersion(version);
                profile.setSceneType(group.sceneType());
                profile.setFactorCode(entry.getKey());
                profile.setWeight(entry.getValue());
                version.getProfiles().add(profile);
            }

            GradeThreshold threshold = new GradeThreshold();
            threshold.setVersion(version);
            threshold.setSceneType(group.sceneType());
            threshold.setGradeAMin(group.gradeAMin());
            threshold.setGradeBMin(group.gradeBMin());
            thresholds.add(threshold);
        }

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

        List<GradeThreshold> thresholds = new ArrayList<>();

        for (SceneGroupRequest group : request.sceneGroups()) {
            List<WeightProfile> profiles = new ArrayList<>();
            for (Map.Entry<FactorCode, BigDecimal> entry : group.weights().entrySet()) {
                WeightProfile profile = new WeightProfile();
                profile.setVersion(version);
                profile.setSceneType(group.sceneType());
                profile.setFactorCode(entry.getKey());
                profile.setWeight(entry.getValue());
                profiles.add(profile);
            }
            version.getProfiles().addAll(profiles);

            GradeThreshold threshold = gradeThresholdRepository.findByVersionIdAndSceneType(id, group.sceneType())
                    .orElseGet(GradeThreshold::new);
            threshold.setVersion(version);
            threshold.setSceneType(group.sceneType());
            threshold.setGradeAMin(group.gradeAMin());
            threshold.setGradeBMin(group.gradeBMin());
            thresholds.add(threshold);
        }
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

        WeightVersion currentVersion = weightVersionRepository.findByIsCurrentTrue().orElse(null);
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

            BigDecimal sum = group.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessException(ErrorCode.WEIGHT_SUM_INVALID,
                        group.sceneType() + " 榜權重加總為 " + sum + "，必須等於 1.000");
            }

            if (group.gradeAMin().compareTo(group.gradeBMin()) <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        group.sceneType() + " 榜的 A 級門檻（" + group.gradeAMin()
                                + "）必須大於 B 級門檻（" + group.gradeBMin() + "）");
            }
        }
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
