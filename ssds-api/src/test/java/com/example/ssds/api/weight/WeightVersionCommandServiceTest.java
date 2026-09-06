package com.example.ssds.api.weight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.dto.ApproveWeightVersionRequest;
import com.example.ssds.api.weight.dto.CreateWeightVersionRequest;
import com.example.ssds.api.weight.dto.SceneGroupRequest;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;

/**
 * FR-08 情境權重組設定的服務層規則（規格書 §FR-08、AC-08-1～AC-08-6）。
 *
 * <p>刻意用 Mockito 而非 @SpringBootTest：這裡驗的全是純業務規則，
 * 不需要資料庫。這些規則的存在理由就是「別讓非法值走到 DB 的 CHECK 才爆」，
 * 用真資料庫測反而會分不出「被服務擋下」還是「被 DB 擋下」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeightVersionCommandServiceTest {

    @Mock
    private WeightVersionRepository weightVersionRepository;

    @Mock
    private GradeThresholdRepository gradeThresholdRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private WeightVersionCommandService service;

    /** v3.0 §5.2 的六個加分因子。扣分因子不進權重，不該出現在請求裡。 */
    private static final List<FactorCode> BONUS_FACTORS =
            List.of(FactorCode.TREND, FactorCode.MARGIN, FactorCode.CVR,
                    FactorCode.PRICE_FIT, FactorCode.FESTIVAL, FactorCode.CLIMATE);

    /** 加總剛好 1.000 的合法權重。 */
    private static Map<FactorCode, BigDecimal> validWeights() {
        Map<FactorCode, BigDecimal> weights = new EnumMap<>(FactorCode.class);
        weights.put(FactorCode.TREND, new BigDecimal("0.300"));
        weights.put(FactorCode.MARGIN, new BigDecimal("0.200"));
        weights.put(FactorCode.CVR, new BigDecimal("0.200"));
        weights.put(FactorCode.PRICE_FIT, new BigDecimal("0.100"));
        weights.put(FactorCode.FESTIVAL, new BigDecimal("0.100"));
        weights.put(FactorCode.CLIMATE, new BigDecimal("0.100"));
        return weights;
    }

    /** 四榜齊全、每榜都合法的請求。各測試再針對要驗的那一點單獨破壞它。 */
    private static CreateWeightVersionRequest validRequest() {
        List<SceneGroupRequest> groups = new ArrayList<>();
        for (SceneType scene : SceneType.values()) {
            groups.add(new SceneGroupRequest(
                    scene, validWeights(), new BigDecimal("85"), new BigDecimal("70")));
        }
        return new CreateWeightVersionRequest("v9", "測試版本", "單元測試用", groups);
    }

    /** 把某一榜換成指定的設定，其餘三榜保持合法。 */
    private static CreateWeightVersionRequest requestWith(SceneGroupRequest replacement) {
        List<SceneGroupRequest> groups = new ArrayList<>();
        for (SceneType scene : SceneType.values()) {
            groups.add(scene == replacement.sceneType()
                    ? replacement
                    : new SceneGroupRequest(scene, validWeights(),
                            new BigDecimal("85"), new BigDecimal("70")));
        }
        return new CreateWeightVersionRequest("v9", "測試版本", "單元測試用", groups);
    }

    private void givenVersionNoIsFree() {
        when(weightVersionRepository.findByVersionNo(any())).thenReturn(Optional.empty());
        when(gradeThresholdRepository.findByVersionIdAndSceneType(anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ------------------------------------------------------------------
    // 審查意見 1：單值域檢查
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("審查意見 1：weight 與門檻的單值域")
    class ValueRange {

        /**
         * 這是「只驗加總」會漏掉的核心案例：-0.5 與 0.5 互相抵銷，
         * 加總仍是 1.000，舊版會一路送到 DB 才撞 ck_weight_profile 變成 500。
         */
        @Test
        @DisplayName("負權重被另一個權重抵銷、加總仍為 1.000 時，仍要擋下並回 400")
        void rejectsNegativeWeightEvenWhenSumIsOne() {
            givenVersionNoIsFree();
            Map<FactorCode, BigDecimal> weights = new LinkedHashMap<>(validWeights());
            weights.put(FactorCode.TREND, new BigDecimal("-0.500"));
            weights.put(FactorCode.MARGIN, new BigDecimal("1.000"));
            // -0.5 + 1.0 + 0.2 + 0.1 + 0.1 + 0.1 = 1.000
            assertThat(weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(BigDecimal.ONE);

            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.VIRAL, weights, new BigDecimal("85"), new BigDecimal("70")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED))
                    .hasMessageContaining("TREND");

            verify(weightVersionRepository, never()).save(any());
        }

        @Test
        @DisplayName("單一權重大於 1.000 要擋下")
        void rejectsWeightAboveOne() {
            givenVersionNoIsFree();
            Map<FactorCode, BigDecimal> weights = new LinkedHashMap<>(validWeights());
            weights.put(FactorCode.TREND, new BigDecimal("1.500"));
            weights.put(FactorCode.MARGIN, new BigDecimal("-1.000"));

            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.VIRAL, weights, new BigDecimal("85"), new BigDecimal("70")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("A 級門檻超過 100 要擋下，不能等 ck_grade_threshold_range")
        void rejectsGradeAboveHundred() {
            givenVersionNoIsFree();
            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.SEASONAL, validWeights(),
                    new BigDecimal("120"), new BigDecimal("70")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED))
                    .hasMessageContaining("A 級門檻");
        }

        @Test
        @DisplayName("B 級門檻為負數要擋下")
        void rejectsNegativeGrade() {
            givenVersionNoIsFree();
            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.SEASONAL, validWeights(),
                    new BigDecimal("85"), new BigDecimal("-10")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED))
                    .hasMessageContaining("B 級門檻");
        }

        @Test
        @DisplayName("邊界值 0.000／1.000 與 0／100 本身是合法的")
        void acceptsBoundaryValues() {
            givenVersionNoIsFree();
            Map<FactorCode, BigDecimal> weights = new LinkedHashMap<>();
            weights.put(FactorCode.TREND, new BigDecimal("1.000"));
            for (FactorCode factor : BONUS_FACTORS) {
                weights.putIfAbsent(factor, new BigDecimal("0.000"));
            }
            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.VIRAL, weights, new BigDecimal("100"), new BigDecimal("0")));

            service.create(request);

            verify(weightVersionRepository).save(any(WeightVersion.class));
        }

        @Test
        @DisplayName("加總不為 1.000 仍回 WEIGHT_SUM_INVALID，不被新的值域檢查搶走")
        void keepsWeightSumErrorCode() {
            givenVersionNoIsFree();
            Map<FactorCode, BigDecimal> weights = new LinkedHashMap<>(validWeights());
            weights.put(FactorCode.TREND, new BigDecimal("0.500"));

            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.VIRAL, weights, new BigDecimal("85"), new BigDecimal("70")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t))
                            .isEqualTo(ErrorCode.WEIGHT_SUM_INVALID));
        }
    }

    // ------------------------------------------------------------------
    // 審查意見 2：核准的原子性
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("審查意見 2：approve 切換 current 的原子性")
    class ApproveAtomicity {

        private WeightVersion draftWithFullProfiles(long id) {
            WeightVersion version = new WeightVersion();
            version.setId(id);
            version.setVersionNo("v9");
            version.setName("測試版本");
            version.setStatus(WeightVersionStatus.DRAFT);
            for (SceneType scene : SceneType.values()) {
                validWeights().forEach((factor, weight) -> {
                    WeightProfile profile = new WeightProfile();
                    profile.setVersion(version);
                    profile.setSceneType(scene);
                    profile.setFactorCode(factor);
                    profile.setWeight(weight);
                    version.getProfiles().add(profile);
                });
            }
            return version;
        }

        @Test
        @DisplayName("查 current 版本走悲觀鎖，不走沒有鎖的 findByIsCurrentTrue")
        void locksCurrentVersionRow() {
            WeightVersion draft = draftWithFullProfiles(9L);
            when(weightVersionRepository.findWithProfilesById(9L)).thenReturn(Optional.of(draft));
            when(weightVersionRepository.findCurrentForUpdate()).thenReturn(Optional.empty());
            when(appUserRepository.getReferenceById(any())).thenReturn(new AppUser());
            when(gradeThresholdRepository.findByVersionId(9L)).thenReturn(List.of());

            service.approve(9L, new ApproveWeightVersionRequest(LocalDate.of(2026, 9, 10)));

            verify(weightVersionRepository).findCurrentForUpdate();
            verify(weightVersionRepository, never()).findByIsCurrentTrue();
            assertThat(draft.getStatus()).isEqualTo(WeightVersionStatus.APPROVED);
            assertThat(draft.isCurrent()).isTrue();
        }

        @Test
        @DisplayName("舊的 current 版本要被退為 RETIRED 且不再是 current")
        void retiresPreviousCurrentVersion() {
            WeightVersion draft = draftWithFullProfiles(9L);
            WeightVersion previous = new WeightVersion();
            previous.setId(8L);
            previous.setStatus(WeightVersionStatus.APPROVED);
            previous.setCurrent(true);

            when(weightVersionRepository.findWithProfilesById(9L)).thenReturn(Optional.of(draft));
            when(weightVersionRepository.findCurrentForUpdate()).thenReturn(Optional.of(previous));
            when(appUserRepository.getReferenceById(any())).thenReturn(new AppUser());
            when(gradeThresholdRepository.findByVersionId(9L)).thenReturn(List.of());

            service.approve(9L, new ApproveWeightVersionRequest(LocalDate.of(2026, 9, 10)));

            assertThat(previous.isCurrent()).isFalse();
            assertThat(previous.getStatus()).isEqualTo(WeightVersionStatus.RETIRED);
        }

        /**
         * 一筆 current 都沒有時鎖不到列，兩個交易會同時通過，
         * 最後由 partial unique index uk_weight_version_current 擋下。
         * 這一支確認那個違反被翻成 409 而不是掉進兜底 handler 變 500。
         */
        @Test
        @DisplayName("撞上 uk_weight_version_current 時翻成 409，不是 500")
        void translatesUniqueViolationToConflict() {
            WeightVersion draft = draftWithFullProfiles(9L);
            when(weightVersionRepository.findWithProfilesById(9L)).thenReturn(Optional.of(draft));
            when(weightVersionRepository.findCurrentForUpdate()).thenReturn(Optional.empty());
            when(appUserRepository.getReferenceById(any())).thenReturn(new AppUser());
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uk_weight_version_current"))
                    .when(weightVersionRepository).flush();

            assertThatThrownBy(() -> service.approve(
                    9L, new ApproveWeightVersionRequest(LocalDate.of(2026, 9, 10))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t))
                            .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        }

        @Test
        @DisplayName("非 DRAFT 的版本不能核准")
        void rejectsNonDraft() {
            WeightVersion approved = draftWithFullProfiles(9L);
            approved.setStatus(WeightVersionStatus.APPROVED);
            when(weightVersionRepository.findWithProfilesById(9L)).thenReturn(Optional.of(approved));

            assertThatThrownBy(() -> service.approve(
                    9L, new ApproveWeightVersionRequest(LocalDate.of(2026, 9, 10))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t))
                            .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

            verify(weightVersionRepository, never()).findCurrentForUpdate();
        }
    }

    // ------------------------------------------------------------------
    // 審查意見 4：create／update 共用同一份建構邏輯
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("審查意見 4：create 與 update 共用建構邏輯")
    class SharedBuildLogic {

        @Test
        @DisplayName("create 產生四榜 × 六因子共 24 筆權重與 4 筆門檻")
        void createBuildsFullMatrix() {
            givenVersionNoIsFree();
            org.mockito.ArgumentCaptor<WeightVersion> saved =
                    org.mockito.ArgumentCaptor.forClass(WeightVersion.class);

            service.create(validRequest());

            verify(weightVersionRepository).save(saved.capture());
            assertThat(saved.getValue().getProfiles()).hasSize(24);
            assertThat(saved.getValue().getStatus()).isEqualTo(WeightVersionStatus.DRAFT);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<GradeThreshold>> thresholds =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(gradeThresholdRepository).saveAll(thresholds.capture());
            assertThat(thresholds.getValue()).hasSize(4);
            assertThat(thresholds.getValue())
                    .extracting(GradeThreshold::getSceneType)
                    .containsExactlyInAnyOrder(SceneType.values());
        }

        /**
         * update 走的是 upsert：既有那一列要被沿用並改值，不能新建一列，
         * 否則 grade_threshold 的複合主鍵 (version_id, scene_type) 會撞。
         */
        @Test
        @DisplayName("update 沿用既有門檻列，不新建")
        void updateReusesExistingThresholdRow() {
            WeightVersion draft = new WeightVersion();
            draft.setId(7L);
            draft.setStatus(WeightVersionStatus.DRAFT);

            GradeThreshold existing = new GradeThreshold();
            existing.setVersion(draft);
            existing.setSceneType(SceneType.VIRAL);
            existing.setGradeAMin(new BigDecimal("60"));
            existing.setGradeBMin(new BigDecimal("40"));

            when(weightVersionRepository.findWithProfilesById(7L)).thenReturn(Optional.of(draft));
            when(weightVersionRepository.findByVersionNo("v9")).thenReturn(Optional.of(draft));
            // 先掛通用的 empty，再掛 VIRAL 的特例：Mockito 以最後註冊的相符樁優先
            when(gradeThresholdRepository.findByVersionIdAndSceneType(anyLong(), any()))
                    .thenReturn(Optional.empty());
            when(gradeThresholdRepository.findByVersionIdAndSceneType(7L, SceneType.VIRAL))
                    .thenReturn(Optional.of(existing));

            service.update(7L, validRequest());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<GradeThreshold>> thresholds =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(gradeThresholdRepository).saveAll(thresholds.capture());

            assertThat(thresholds.getValue()).hasSize(4);
            // 同一個實例被沿用，且值已被請求覆寫
            assertThat(thresholds.getValue()).contains(existing);
            assertThat(existing.getGradeAMin()).isEqualByComparingTo(new BigDecimal("85"));
            assertThat(existing.getGradeBMin()).isEqualByComparingTo(new BigDecimal("70"));
            assertThat(draft.getProfiles()).hasSize(24);
        }

        @Test
        @DisplayName("已核准的版本不可編輯（AC-08-2）")
        void rejectsEditingApprovedVersion() {
            WeightVersion approved = new WeightVersion();
            approved.setId(7L);
            approved.setStatus(WeightVersionStatus.APPROVED);
            when(weightVersionRepository.findWithProfilesById(7L)).thenReturn(Optional.of(approved));

            assertThatThrownBy(() -> service.update(7L, validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t))
                            .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        }
    }

    // ------------------------------------------------------------------
    // 既有規則的回歸保護
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("既有規則")
    class ExistingRules {

        @Test
        @DisplayName("缺一榜就擋下（AC-08-1 四榜必填）")
        void rejectsMissingScene() {
            givenVersionNoIsFree();
            List<SceneGroupRequest> groups = new ArrayList<>();
            for (SceneType scene : List.of(SceneType.VIRAL, SceneType.FESTIVAL, SceneType.SEASONAL)) {
                groups.add(new SceneGroupRequest(scene, validWeights(),
                        new BigDecimal("85"), new BigDecimal("70")));
            }
            CreateWeightVersionRequest request =
                    new CreateWeightVersionRequest("v9", "測試版本", null, groups);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED))
                    .hasMessageContaining("REPLENISHMENT");
        }

        @Test
        @DisplayName("帶入扣分因子就擋下：扣分因子固定生效、不進權重（§5.2）")
        void rejectsPenaltyFactor() {
            givenVersionNoIsFree();
            Map<FactorCode, BigDecimal> weights = new LinkedHashMap<>(validWeights());
            weights.remove(FactorCode.CLIMATE);
            weights.put(FactorCode.REVIEW_RISK, new BigDecimal("0.100"));

            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.VIRAL, weights, new BigDecimal("85"), new BigDecimal("70")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("A 級門檻不高於 B 級就擋下")
        void rejectsInvertedGradeOrder() {
            givenVersionNoIsFree();
            CreateWeightVersionRequest request = requestWith(new SceneGroupRequest(
                    SceneType.VIRAL, validWeights(),
                    new BigDecimal("70"), new BigDecimal("85")));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("版本號重複回 409 DUPLICATE_RESOURCE")
        void rejectsDuplicateVersionNo() {
            WeightVersion existing = new WeightVersion();
            existing.setId(1L);
            when(weightVersionRepository.findByVersionNo("v9")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.create(validRequest()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(errorCodeOf(t))
                            .isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        }
    }
}
