package com.example.ssds.api.festival.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.festival.dto.FestivalResponse;
import com.example.ssds.api.festival.dto.FestivalWindowStatus;
import com.example.ssds.core.domain.CalendarType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository.FestivalCategoryLink;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FestivalQueryServiceTest {

    /** 與共用庫假資料一致：零食 45 天、日用品 30 天。 */
    private static final long SNACK = 10L;
    private static final long DAILY = 20L;

    private FestivalCalendarRepository festivalCalendarRepository;
    private ItemFestivalAffinityRepository itemFestivalAffinityRepository;
    private CategoryLeadTimeRepository categoryLeadTimeRepository;
    private FestivalQueryService service;

    @BeforeEach
    void setUp() {
        festivalCalendarRepository = mock(FestivalCalendarRepository.class);
        itemFestivalAffinityRepository = mock(ItemFestivalAffinityRepository.class);
        categoryLeadTimeRepository = mock(CategoryLeadTimeRepository.class);
        service = new FestivalQueryService(
                festivalCalendarRepository, itemFestivalAffinityRepository, categoryLeadTimeRepository);
    }

    /** 沒有檔期時不該再往下查關聯與前置天數。 */
    @Test
    void emptyYearShortCircuits() {
        when(festivalCalendarRepository.findByYearOrderByFestivalDateAsc(anyInt()))
                .thenReturn(List.of());

        assertEquals(List.of(), service.getFestivalsByYear(2099, LocalDate.of(2026, 9, 22)));
        verify(itemFestivalAffinityRepository, org.mockito.Mockito.never()).findCategoryLinks(any());
    }

    /**
     * 窗狀態取所有關聯品類中權重最高者。
     *
     * <p>中秋 2026-09-25、評估日 2026-09-22 → d=3。零食 L=45 → 3 &lt; 45 → 0.50 補單期；
     * 日用品 L=30 → 一樣 0.50。兩者取最大仍是 0.50。
     */
    @Test
    void statusTakesBestWindowAmongRelatedCategories() {
        givenFestival("MID_AUTUMN", LocalDate.of(2026, 9, 25), CalendarType.LUNAR);
        givenLinks(link("MID_AUTUMN", SNACK), link("MID_AUTUMN", DAILY));
        givenLeadTimes(leadTime(SNACK, 45), leadTime(DAILY, 30));

        FestivalResponse response = service
                .getFestivalsByYear(2026, LocalDate.of(2026, 9, 22))
                .getFirst();

        assertEquals(FestivalWindowStatus.SUPPLEMENTARY, response.windowStatus());
        assertEquals(2, response.relatedCategoryCount());
    }

    /** 雙 11 2026-11-11、評估日 2026-09-22 → d=50。零食 L=45 → 45 ≤ 50 ≤ 75 → 黃金備貨期。 */
    @Test
    void inGoldenWindow() {
        givenFestival("DOUBLE_11", LocalDate.of(2026, 11, 11), CalendarType.SOLAR);
        givenLinks(link("DOUBLE_11", SNACK));
        givenLeadTimes(leadTime(SNACK, 45));

        assertEquals(FestivalWindowStatus.IN_WINDOW, service
                .getFestivalsByYear(2026, LocalDate.of(2026, 9, 22))
                .getFirst().windowStatus());
    }

    /** 節慶日已過一律 PASSED，與有沒有關聯品類無關。 */
    @Test
    void passedRegardlessOfCategories() {
        givenFestival("FATHERS_DAY", LocalDate.of(2026, 8, 8), CalendarType.SOLAR);
        givenLinks();
        givenLeadTimes();

        assertEquals(FestivalWindowStatus.PASSED, service
                .getFestivalsByYear(2026, LocalDate.of(2026, 9, 22))
                .getFirst().windowStatus());
    }

    /** 沒有品項關聯這個節慶，就沒有備貨期可言。 */
    @Test
    void noRelatedCategoryMeansNotStarted() {
        givenFestival("NEW_YEAR_EVE", LocalDate.of(2026, 12, 31), CalendarType.SOLAR);
        givenLinks();
        givenLeadTimes();

        FestivalResponse response = service
                .getFestivalsByYear(2026, LocalDate.of(2026, 9, 22))
                .getFirst();

        assertEquals(FestivalWindowStatus.NOT_STARTED, response.windowStatus());
        assertEquals(0, response.relatedCategoryCount());
    }

    /** 下拉選項：同一節慶跨年度只回一筆（原 ProductReferenceQueryService 的行為）。 */
    @Test
    void festivalOptionsAreDeduplicatedAcrossYears() {
        when(festivalCalendarRepository.findAllByOrderByFestivalNameAscYearDesc())
                .thenReturn(List.of(
                        festival("MID_AUTUMN", LocalDate.of(2027, 9, 15), CalendarType.LUNAR, 2027),
                        festival("MID_AUTUMN", LocalDate.of(2026, 9, 25), CalendarType.LUNAR, 2026)));

        var options = service.getFestivalOptions();

        assertEquals(1, options.size());
        assertEquals("MID_AUTUMN", options.getFirst().festivalCode());
    }

    // ---- 測試資料組裝 ----

    private void givenFestival(String code, LocalDate date, CalendarType type) {
        when(festivalCalendarRepository.findByYearOrderByFestivalDateAsc(anyInt()))
                .thenReturn(List.of(festival(code, date, type, date.getYear())));
    }

    private void givenLinks(FestivalCategoryLink... links) {
        when(itemFestivalAffinityRepository.findCategoryLinks(any())).thenReturn(List.of(links));
    }

    private void givenLeadTimes(CategoryLeadTime... leadTimes) {
        when(categoryLeadTimeRepository.findAllById(any())).thenReturn(List.of(leadTimes));
    }

    private static FestivalCalendar festival(
            String code, LocalDate date, CalendarType type, int year) {
        return FestivalCalendar.builder()
                .id((long) code.hashCode())
                .festivalCode(code)
                .festivalName(code)
                .calendarType(type)
                .festivalDate(date)
                .year((short) year)
                .build();
    }

    private static FestivalCategoryLink link(String festivalCode, Long categoryId) {
        FestivalCategoryLink link = mock(FestivalCategoryLink.class);
        when(link.getFestivalCode()).thenReturn(festivalCode);
        when(link.getCategoryId()).thenReturn(categoryId);
        return link;
    }

    private static CategoryLeadTime leadTime(Long categoryId, int days) {
        return CategoryLeadTime.builder()
                .category(Category.builder().id(categoryId).name("c" + categoryId).build())
                .categoryId(categoryId)
                .leadTimeDays(days)
                .build();
    }
}
