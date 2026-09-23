package com.example.ssds.api.festival.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.festival.dto.FestivalCreateRequest;
import com.example.ssds.api.festival.dto.FestivalUpdateRequest;
import com.example.ssds.core.calendar.IcuLunarDateConverter;
import com.example.ssds.core.domain.CalendarType;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.repository.FestivalCalendarRepository;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** AC-17-1：農曆檔期的國曆日期由換算產生，不接受人工輸入。 */
class FestivalCommandServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    private FestivalCalendarRepository festivalCalendarRepository;
    private FestivalQueryService festivalQueryService;
    private FestivalCommandService service;

    @BeforeEach
    void setUp() {
        festivalCalendarRepository = mock(FestivalCalendarRepository.class);
        festivalQueryService = mock(FestivalQueryService.class);
        // 換算用真實實作：這條 AC 驗的就是換算結果，換成 mock 等於什麼都沒驗
        service = new FestivalCommandService(
                festivalCalendarRepository, festivalQueryService, new IcuLunarDateConverter());

        when(festivalCalendarRepository.findByFestivalCodeAndYear(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        when(festivalCalendarRepository.saveAndFlush(any(FestivalCalendar.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** AC-17-1 的核心：只給農曆 8/15，國曆 2026-09-25 要自己算出來。 */
    @Test
    void lunarFestivalDateIsConvertedNotTyped() {
        service.create(new FestivalCreateRequest(
                "MID_AUTUMN", "中秋節", CalendarType.LUNAR, 2026, null, 8, 15), TODAY);

        verify(festivalCalendarRepository).saveAndFlush(argThatHasDate(LocalDate.of(2026, 9, 25)));
    }

    /** 2027 年也不必人工建表，這就是「不需逐年人工維護」。 */
    @Test
    void lunarConversionWorksForFutureYears() {
        service.create(new FestivalCreateRequest(
                "MID_AUTUMN", "中秋節", CalendarType.LUNAR, 2027, null, 8, 15), TODAY);

        verify(festivalCalendarRepository).saveAndFlush(argThatHasDate(LocalDate.of(2027, 9, 15)));
    }

    @Test
    void lunarFestivalWithoutLunarFieldsIsRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                new FestivalCreateRequest(
                        "MID_AUTUMN", "中秋節", CalendarType.LUNAR, 2026,
                        LocalDate.of(2026, 9, 25), null, null),
                TODAY));

        assertEquals(ErrorCode.VALIDATION_FAILED, e.getErrorCode());
        verify(festivalCalendarRepository, never()).saveAndFlush(any());
    }

    @Test
    void solarFestivalWithoutDateIsRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                new FestivalCreateRequest(
                        "HALLOWEEN", "萬聖節", CalendarType.SOLAR, 2026, null, null, null),
                TODAY));

        assertEquals(ErrorCode.VALIDATION_FAILED, e.getErrorCode());
    }

    /** uk_festival (festival_code, year) 的重複要回 409，不是讓 DB 丟例外。 */
    @Test
    void duplicateCodeAndYearIsConflict() {
        when(festivalCalendarRepository.findByFestivalCodeAndYear("MID_AUTUMN", 2026))
                .thenReturn(Optional.of(new FestivalCalendar()));

        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                new FestivalCreateRequest(
                        "MID_AUTUMN", "中秋節", CalendarType.LUNAR, 2026, null, 8, 15),
                TODAY));

        assertEquals(ErrorCode.DUPLICATE_RESOURCE, e.getErrorCode());
    }

    /** ck_festival_year：日期年份必須等於 year 欄，先擋成 400 不要讓 CHECK 冒成 500。 */
    @Test
    void solarDateOutsideDeclaredYearIsRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(
                new FestivalCreateRequest(
                        "NEW_YEAR_EVE", "跨年", CalendarType.SOLAR, 2026,
                        LocalDate.of(2027, 1, 1), null, null),
                TODAY));

        assertEquals(ErrorCode.VALIDATION_FAILED, e.getErrorCode());
    }

    @Test
    void updateMissingFestivalIsNotFound() {
        when(festivalCalendarRepository.findById(99999L)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class, () -> service.update(
                99999L,
                new FestivalUpdateRequest("不存在", LocalDate.of(2026, 1, 1), null, null),
                TODAY));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, e.getErrorCode());
    }

    /** 已被 score_factor.driving_festival_id 引用的檔期刪不掉，要回 409 而不是 500。 */
    @Test
    void deletingReferencedFestivalIsConflict() {
        FestivalCalendar festival = FestivalCalendar.builder()
                .id(7L).festivalCode("MID_AUTUMN").year((short) 2026)
                .festivalDate(LocalDate.of(2026, 9, 25)).calendarType(CalendarType.LUNAR)
                .build();
        when(festivalCalendarRepository.findById(7L)).thenReturn(Optional.of(festival));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("fk"))
                .when(festivalCalendarRepository).flush();

        BusinessException e = assertThrows(BusinessException.class, () -> service.delete(7L));

        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.getErrorCode());
    }

    private static FestivalCalendar argThatHasDate(LocalDate expected) {
        return org.mockito.ArgumentMatchers.argThat(
                festival -> expected.equals(festival.getFestivalDate()));
    }
}
