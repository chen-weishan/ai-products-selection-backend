package com.example.ssds.api.common.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "success", "data", "error", "timestamp" })
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        OffsetDateTime timestamp) {

    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    /** 規格書 §8.1 的時間範例為秒級，截去小數秒以對齊。 */
    private static OffsetDateTime now() {
        return OffsetDateTime.now(API_ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, now());
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, now());
    }
}
