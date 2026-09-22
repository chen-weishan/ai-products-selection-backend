package com.example.ssds.api.festival.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.festival.ClimateProperties;
import com.example.ssds.api.festival.dto.CategoryClimateProfileResponse;
import com.example.ssds.api.festival.dto.CategoryClimateProfileUpdateRequest;
import com.example.ssds.api.festival.dto.CategoryLeadTimeUpdateRequest;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryClimateProfile;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.repository.CategoryClimateProfileRepository;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.CategoryRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryProfileServiceTest {

    private static final Long SNACK = 10L;

    private CategoryRepository categoryRepository;
    private CategoryLeadTimeRepository categoryLeadTimeRepository;
    private CategoryClimateProfileRepository categoryClimateProfileRepository;
    private CategoryProfileService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        categoryLeadTimeRepository = mock(CategoryLeadTimeRepository.class);
        categoryClimateProfileRepository = mock(CategoryClimateProfileRepository.class);
        service = new CategoryProfileService(
                categoryRepository, categoryLeadTimeRepository, categoryClimateProfileRepository,
                new ClimateProperties(new BigDecimal("12.0"), "TW_TPE"));

        when(categoryRepository.findById(SNACK)).thenReturn(Optional.of(
                Category.builder().id(SNACK).name("零食").build()));
        when(categoryLeadTimeRepository.save(any(CategoryLeadTime.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(categoryClimateProfileRepository.save(any(CategoryClimateProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * 未設定的欄位要回 null，不可用 0 代替。
     *
     * <p>AC-17-5 的判準是「有沒有資料」：0 會讓「適溫 0°C」與「沒填」變成同一件事，
     * 正是老師在 FR-04 抓到的「資料不足未明確標示」同一型問題。
     */
    @Test
    void unsetFieldsAreNullNotZero() {
        when(categoryRepository.findAll()).thenReturn(List.of(
                Category.builder().id(SNACK).name("零食").build(),
                Category.builder().id(40L).name("小家電").build()));
        when(categoryLeadTimeRepository.findAll()).thenReturn(List.of(
                CategoryLeadTime.builder().categoryId(40L).leadTimeDays(30).build()));
        when(categoryClimateProfileRepository.findAll()).thenReturn(List.of(
                CategoryClimateProfile.builder()
                        .categoryId(SNACK)
                        .idealTempMin(new BigDecimal("18.0"))
                        .idealTempMax(new BigDecimal("26.0"))
                        .tolerance(new BigDecimal("12.0"))
                        .build()));

        var rows = service.listProfiles();

        assertEquals(2, rows.size());
        // 零食：有適溫、沒前置天數
        assertNull(rows.getFirst().leadTimeDays());
        assertEquals(0, rows.getFirst().idealTempMin().compareTo(new BigDecimal("18.0")));
        // 小家電：有前置天數、沒適溫 —— AC-17-5 第三層的例子
        assertEquals(30, rows.get(1).leadTimeDays());
        assertNull(rows.get(1).idealTempMin());
        assertNull(rows.get(1).tolerance());
    }

    /** PUT 是 upsert：原本沒有這一列也要建得起來。 */
    @Test
    void leadTimeIsCreatedWhenAbsent() {
        when(categoryLeadTimeRepository.findById(SNACK)).thenReturn(Optional.empty());

        var response = service.updateLeadTime(SNACK, new CategoryLeadTimeUpdateRequest(45));

        assertEquals(45, response.leadTimeDays());
        assertEquals("零食", response.categoryName());
    }

    @Test
    void unknownCategoryIsNotFound() {
        when(categoryRepository.findById(99999L)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.updateLeadTime(99999L, new CategoryLeadTimeUpdateRequest(30)));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, e.getErrorCode());
    }

    /** tolerance 留空時沿用 ClimateProperties 的系統預設，不在 entity 或 DB 各放一份。 */
    @Test
    void toleranceFallsBackToSystemDefault() {
        when(categoryClimateProfileRepository.findById(SNACK)).thenReturn(Optional.empty());

        CategoryClimateProfileResponse response = service.updateClimateProfile(
                SNACK, new CategoryClimateProfileUpdateRequest(
                        new BigDecimal("18.0"), new BigDecimal("26.0"), null));

        assertEquals(0, response.tolerance().compareTo(new BigDecimal("12.0")));
    }

    @Test
    void invertedTemperatureRangeIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.updateClimateProfile(SNACK, new CategoryClimateProfileUpdateRequest(
                        new BigDecimal("30.0"), new BigDecimal("18.0"), null)));

        assertEquals(ErrorCode.VALIDATION_FAILED, e.getErrorCode());
    }

    @Test
    void nonPositiveToleranceIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.updateClimateProfile(SNACK, new CategoryClimateProfileUpdateRequest(
                        new BigDecimal("18.0"), new BigDecimal("26.0"), BigDecimal.ZERO)));

        assertEquals(ErrorCode.VALIDATION_FAILED, e.getErrorCode());
    }
}
