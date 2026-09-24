package com.example.ssds.api.festival.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.festival.dto.FestivalCreateRequest;
import com.example.ssds.api.festival.dto.FestivalResponse;
import com.example.ssds.api.festival.dto.FestivalUpdateRequest;
import com.example.ssds.core.calendar.LunarDateConverter;
import com.example.ssds.core.domain.CalendarType;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.repository.FestivalCalendarRepository;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 節慶檔期的維護（規格書 §FR-17-1、AC-17-1、畫面 S-20 標記 2、權限矩陣第 20 列）。
 *
 * <p>AC-17-1：LUNAR 檔期的國曆日期一律由農曆換算產生，不接受人工輸入——
 * 人工逐年輸入正是這條驗收準則要消滅的事。
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FestivalCommandService {

    private final FestivalCalendarRepository festivalCalendarRepository;
    private final FestivalQueryService festivalQueryService;
    private final LunarDateConverter lunarDateConverter;

    /**
     * 新增檔期。
     *
     * @throws BusinessException {@code DUPLICATE_RESOURCE} 當 (festivalCode, year) 已存在；
     *                           {@code VALIDATION_FAILED} 當曆別與日期欄位不相符
     */
    public FestivalResponse create(FestivalCreateRequest request, LocalDate evaluationDate) {

        festivalCalendarRepository
                .findByFestivalCodeAndYear(request.festivalCode(), request.year())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                            "%d 年的 %s 檔期已存在".formatted(request.year(), request.festivalCode()));
                });

        LocalDate festivalDate = resolveFestivalDate(
                request.calendarType(), request.year(),
                request.festivalDate(), request.lunarMonth(), request.lunarDay());

        FestivalCalendar festival = FestivalCalendar.builder()
                .festivalCode(request.festivalCode())
                .festivalName(request.festivalName())
                .calendarType(request.calendarType())
                .festivalDate(festivalDate)
                .year((short) request.year().intValue())
                .build();

        FestivalCalendar saved = festivalCalendarRepository.saveAndFlush(festival);
        return festivalQueryService.toResponse(saved, evaluationDate);
    }

    /**
     * 編輯檔期。{@code festivalCode} 與 {@code year} 是唯一鍵的組成，不開放修改——
     * 要改代碼或年度等於換一個檔期，請刪除後新增。
     *
     * @throws BusinessException {@code RESOURCE_NOT_FOUND}、{@code VALIDATION_FAILED}
     */
    public FestivalResponse update(Long id, FestivalUpdateRequest request, LocalDate evaluationDate) {

        FestivalCalendar festival = festivalCalendarRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到節慶檔期 id=" + id));

        LocalDate festivalDate = resolveFestivalDate(
                festival.getCalendarType(), (int) festival.getYear(),
                request.festivalDate(), request.lunarMonth(), request.lunarDay());

        festival.setFestivalName(request.festivalName());
        festival.setFestivalDate(festivalDate);

        festivalCalendarRepository.flush();
        return festivalQueryService.toResponse(festival, evaluationDate);
    }

    /**
     * 刪除檔期。
     *
     * <p>V30 之後 {@code score_factor.driving_festival_id} 外鍵到本表，已被分數快照
     * 引用的檔期刪不掉。讓它以 409 回應而不是讓外鍵違反冒成 500。
     *
     * @throws BusinessException {@code RESOURCE_NOT_FOUND}、{@code INVALID_STATE_TRANSITION}
     */
    public void delete(Long id) {

        FestivalCalendar festival = festivalCalendarRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到節慶檔期 id=" + id));

        festivalCalendarRepository.delete(festival);
        try {
            festivalCalendarRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "此檔期已被評分結果引用，不可刪除（id=" + id + "）");
        }
    }

    /**
     * 決定國曆日期：SOLAR 用輸入值，LUNAR 一律由換算產生（AC-17-1）。
     *
     * <p>跨欄位的互斥檢查寫在這裡而不是 Bean Validation：
     * 欄位層註解表達不了「SOLAR 要 A 欄、LUNAR 要 B 欄」這種相依關係。
     */
    private LocalDate resolveFestivalDate(
            CalendarType calendarType, int year,
            LocalDate festivalDate, Integer lunarMonth, Integer lunarDay) {

        LocalDate resolved;
        if (calendarType == CalendarType.LUNAR) {
            if (lunarMonth == null || lunarDay == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "農曆檔期必須提供 lunarMonth 與 lunarDay，國曆日期由系統換算");
            }
            try {
                resolved = lunarDateConverter.toSolar(year, lunarMonth, lunarDay);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, e.getMessage());
            }
        } else {
            if (festivalDate == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "國曆檔期必須提供 festivalDate");
            }
            resolved = festivalDate;
        }

        // festival_calendar 有 ck_festival_year（日期年份必須等於 year 欄）。
        // 農曆十一、十二月換算出來會落到下一個西元年，先擋在這裡回 400，
        // 不要讓 CHECK 違反冒成 500。
        if (resolved.getYear() != year) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "換算後的國曆日期 %s 不在 %d 年內，請改建到該年度".formatted(resolved, year));
        }
        return resolved;
    }
}
