package com.example.ssds.api.festival.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.festival.ClimateProperties;
import com.example.ssds.api.festival.dto.CategoryClimateProfileResponse;
import com.example.ssds.api.festival.dto.CategoryClimateProfileUpdateRequest;
import com.example.ssds.api.festival.dto.CategoryLeadTimeResponse;
import com.example.ssds.api.festival.dto.CategoryLeadTimeUpdateRequest;
import com.example.ssds.api.festival.dto.CategoryProfileResponse;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryClimateProfile;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.repository.CategoryClimateProfileRepository;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.CategoryRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 品類層級的兩張設定表（S-20 標記 3、5、權限矩陣第 20 列）。
 *
 * <ul>
 *   <li>前置天數 {@code category_lead_time}：AC-17-3 要求同一份資料同時供 FR-17
 *       節慶時間窗與 FR-16 時效落差使用，<b>只維護一份</b>。FR-16 的
 *       {@code ProductSourcingCandidateService} 已在讀這張表，不可另建第二份。</li>
 *   <li>適溫區間 {@code category_climate_profile}：AC-17-5 的第二層 fallback。</li>
 * </ul>
 *
 * <p>兩支都是 {@code PUT}＝全量替換單列資源，不存在則建立（upsert）。
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CategoryProfileService {

    /** TODO FR-01 完成後改為 SecurityContextHolder 取得的當前登入者。 */
    private static final long TEMP_OPERATOR_ID = 3L;

    /** 規格書 §3.2：系統時區統一 Asia/Taipei。與專案其餘服務的寫法一致。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final CategoryRepository categoryRepository;
    private final CategoryLeadTimeRepository categoryLeadTimeRepository;
    private final CategoryClimateProfileRepository categoryClimateProfileRepository;
    private final ClimateProperties climateProperties;

    /**
     * S-20 標記 3、5 兩張表的現值，依品類 id 排序。
     *
     * <p>三次查詢撈完全部品類：品類本身、前置天數、適溫區間，之後在記憶體合併。
     * 逐品類查會變成 1 + 2N 次。
     *
     * <p>規格書 §9 沒有這支端點，是為了讓 S-20 顯示現值而新增的設計決定。
     */
    @Transactional(readOnly = true)
    public List<CategoryProfileResponse> listProfiles() {

        Map<Long, CategoryLeadTime> leadTimes = categoryLeadTimeRepository.findAll().stream()
                .collect(Collectors.toMap(CategoryLeadTime::getCategoryId, row -> row, (a, b) -> a));
        Map<Long, CategoryClimateProfile> profiles =
                categoryClimateProfileRepository.findAll().stream()
                        .collect(Collectors.toMap(
                                CategoryClimateProfile::getCategoryId, row -> row, (a, b) -> a));

        return categoryRepository.findAll().stream()
                .sorted(Comparator.comparing(Category::getId))
                .map(category -> {
                    CategoryLeadTime leadTime = leadTimes.get(category.getId());
                    CategoryClimateProfile profile = profiles.get(category.getId());
                    return new CategoryProfileResponse(
                            category.getId(),
                            category.getName(),
                            leadTime != null ? leadTime.getLeadTimeDays() : null,
                            profile != null ? profile.getIdealTempMin() : null,
                            profile != null ? profile.getIdealTempMax() : null,
                            profile != null ? profile.getTolerance() : null);
                })
                .toList();
    }

    /**
     * 維護品類前置天數。
     *
     * @throws BusinessException {@code RESOURCE_NOT_FOUND} 當品類不存在
     */
    public CategoryLeadTimeResponse updateLeadTime(
            Long categoryId, CategoryLeadTimeUpdateRequest request) {

        Category category = requireCategory(categoryId);

        CategoryLeadTime leadTime = categoryLeadTimeRepository.findById(categoryId)
                .orElseGet(() -> CategoryLeadTime.builder().category(category).build());

        leadTime.setLeadTimeDays(request.leadTimeDays());
        leadTime.setUpdatedBy(TEMP_OPERATOR_ID);
        leadTime.setUpdatedAt(OffsetDateTime.now(BUSINESS_ZONE));

        CategoryLeadTime saved = categoryLeadTimeRepository.save(leadTime);
        return new CategoryLeadTimeResponse(
                category.getId(), category.getName(), saved.getLeadTimeDays());
    }

    /**
     * 維護品類預設適溫區間。
     *
     * @throws BusinessException {@code RESOURCE_NOT_FOUND} 當品類不存在；
     *                           {@code VALIDATION_FAILED} 當上下限顛倒或容忍範圍非正
     */
    public CategoryClimateProfileResponse updateClimateProfile(
            Long categoryId, CategoryClimateProfileUpdateRequest request) {

        Category category = requireCategory(categoryId);

        if (request.idealTempMin().compareTo(request.idealTempMax()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "適溫下限不可大於上限");
        }
        // tolerance 留空代表沿用系統預設（§FR-17-2 的 TOLERANCE=12），
        // 預設值只有 ClimateProperties 一個來源，不在 entity 或 DB 各放一份。
        BigDecimal tolerance = request.tolerance() != null
                ? request.tolerance()
                : climateProperties.defaultTolerance();
        if (tolerance.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "容忍範圍必須為正數");
        }

        CategoryClimateProfile profile = categoryClimateProfileRepository.findById(categoryId)
                .orElseGet(() -> CategoryClimateProfile.builder().category(category).build());

        profile.setIdealTempMin(request.idealTempMin());
        profile.setIdealTempMax(request.idealTempMax());
        profile.setTolerance(tolerance);

        CategoryClimateProfile saved = categoryClimateProfileRepository.save(profile);
        return new CategoryClimateProfileResponse(
                category.getId(), category.getName(),
                saved.getIdealTempMin(), saved.getIdealTempMax(), saved.getTolerance());
    }

    private Category requireCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到品類 id=" + categoryId));
    }
}
