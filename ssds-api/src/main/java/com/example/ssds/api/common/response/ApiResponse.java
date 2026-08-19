package com.example.ssds.api.common.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 規格書 §8.1 統一成功回應格式。
 *
 * @param success 請求是否成功
 * @param data 回應資料
 * @param timestamp 回應產生時間
 * @param <T> 回應資料型別
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        OffsetDateTime timestamp) {

    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    /** 建立成功回應。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, OffsetDateTime.now(API_ZONE));
    }
}
