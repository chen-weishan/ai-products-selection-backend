package com.example.ssds.api.common.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 規格書 §8.1 統一分頁資料格式。
 *
 * <p>此物件放在 {@link ApiResponse#data()} 中，避免直接將 Spring Data 的
 * {@link Page} 實作細節暴露為 API 契約。
 *
 * @param content 當頁資料
 * @param page 頁碼，從 0 開始
 * @param size 每頁筆數
 * @param totalElements 符合條件的總筆數
 * @param totalPages 總頁數
 * @param <T> 當頁資料型別
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /** 將 Spring Data Page 轉換為穩定的 API 分頁格式。 */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                List.copyOf(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
