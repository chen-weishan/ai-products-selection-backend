package com.example.ssds.api.festival.service;

import com.example.ssds.api.festival.FestivalMapper;
import com.example.ssds.api.festival.FestivalWindowStatusResolver;
import com.example.ssds.api.festival.dto.FestivalResponse;
import com.example.ssds.api.festival.dto.FestivalWindowStatus;
import com.example.ssds.api.product.dto.FestivalOptionResponse;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository.FestivalCategoryLink;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 節慶檔期的唯讀查詢（規格書 §FR-17-1、§9 API 清單、畫面 S-20 標記 1、2）。
 *
 * <p>AC-17-2：時間窗權重每次都現算，不是存在資料庫的欄位，所以評估日由呼叫端傳入。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FestivalQueryService {

    private final FestivalCalendarRepository festivalCalendarRepository;
    private final ItemFestivalAffinityRepository itemFestivalAffinityRepository;
    private final CategoryLeadTimeRepository categoryLeadTimeRepository;

    /**
     * 年度檔期清單（S-20 標記 2），附每個檔期的關聯品類數與當前窗狀態。
     *
     * <p>排序固定為節慶日由早到晚：S-20 的時間窗示意（標記 1）與本清單吃同一份資料，
     * 兩邊排序必須一致。之後若要改排序，兩處一起改。
     *
     * @param year           西元年
     * @param evaluationDate 評估日，通常是今天；由呼叫端傳入才測得動
     */
    public List<FestivalResponse> getFestivalsByYear(int year, LocalDate evaluationDate) {

        List<FestivalCalendar> festivals =
                festivalCalendarRepository.findByYearOrderByFestivalDateAsc(year);
        if (festivals.isEmpty()) {
            return List.of();
        }

        // 兩次查詢跑完全部檔期：關聯品類、品類前置天數。
        // 不在下面的迴圈裡查任何東西 —— 那會變成 N+1。
        Map<String, Set<Long>> categoryIdsByFestivalCode = loadRelatedCategoryIds(
                festivals.stream().map(FestivalCalendar::getFestivalCode).collect(Collectors.toSet()));

        Set<Long> allCategoryIds = categoryIdsByFestivalCode.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        Map<Long, Integer> leadTimeDaysByCategoryId = loadLeadTimeDays(allCategoryIds);

        return festivals.stream()
                .map(festival -> {
                    Set<Long> categoryIds = categoryIdsByFestivalCode
                            .getOrDefault(festival.getFestivalCode(), Set.of());
                    FestivalWindowStatus status = FestivalWindowStatusResolver.resolve(
                            evaluationDate, festival.getFestivalDate(),
                            categoryIds, leadTimeDaysByCategoryId);
                    return FestivalMapper.toResponse(festival, categoryIds.size(), status);
                })
                .toList();
    }

    /**
     * 單一檔期的回應，供 {@link FestivalCommandService} 在建立／更新後回傳。
     *
     * <p>清單版一次撈全年，這支只撈一個檔期——建立／更新是單筆操作，
     * 為了一筆去撈全年的關聯反而更慢。
     */
    public FestivalResponse toResponse(FestivalCalendar festival, LocalDate evaluationDate) {

        Set<Long> categoryIds = loadRelatedCategoryIds(Set.of(festival.getFestivalCode()))
                .getOrDefault(festival.getFestivalCode(), Set.of());
        Map<Long, Integer> leadTimeDaysByCategoryId = loadLeadTimeDays(categoryIds);

        return FestivalMapper.toResponse(
                festival,
                categoryIds.size(),
                FestivalWindowStatusResolver.resolve(
                        evaluationDate, festival.getFestivalDate(),
                        categoryIds, leadTimeDaysByCategoryId));
    }

    /**
     * 品項表單的節慶下拉選項：同一個節慶跨年度只回一筆。
     *
     * <p>行為與原本 {@code ProductReferenceQueryService.getFestivals()} 相同。
     * 規格書 §9 要求 {@code GET /festivals} 同時支援 {@code ?year=} 回年度檔期，
     * 一個路徑不能有兩個 handler，因此把原本那支一併收攏到這裡。
     * <b>回應欄位不可更動</b>，否則前端的品項表單會壞掉。
     */
    public List<FestivalOptionResponse> getFestivalOptions() {

        LinkedHashMap<String, String> nameByCode = new LinkedHashMap<>();
        festivalCalendarRepository.findAllByOrderByFestivalNameAscYearDesc()
                .forEach(festival -> nameByCode.putIfAbsent(
                        festival.getFestivalCode(), festival.getFestivalName()));

        return nameByCode.entrySet().stream()
                .map(entry -> new FestivalOptionResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** 節慶代碼 → 關聯到的品類 id 集合。沒有任何關聯的節慶不會出現在 map 裡。 */
    private Map<String, Set<Long>> loadRelatedCategoryIds(Collection<String> festivalCodes) {

        if (festivalCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<Long>> result = new HashMap<>();
        for (FestivalCategoryLink link
                : itemFestivalAffinityRepository.findCategoryLinks(festivalCodes)) {
            result.computeIfAbsent(link.getFestivalCode(), code -> new HashSet<>())
                    .add(link.getCategoryId());
        }
        return result;
    }

    /** 品類 id → 前置天數。{@code findAllById} 是 JpaRepository 內建，不必自己加方法。 */
    private Map<Long, Integer> loadLeadTimeDays(Collection<Long> categoryIds) {

        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryLeadTimeRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(
                        CategoryLeadTime::getCategoryId,
                        CategoryLeadTime::getLeadTimeDays,
                        (a, b) -> a));
    }
}
