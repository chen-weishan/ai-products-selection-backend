package com.example.ssds.api.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"success", "data", "error", "timestamp"})
public record AppResponse<T>(
        boolean success,
        T data,
        ApiError error,
        OffsetDateTime timestamp
) {
    private static final ZoneId API_TIME_ZONE = ZoneId.of("Asia/Taipei");

    public static <T> AppResponse<T> success(T data) {
        return new AppResponse<>(true, data, null, OffsetDateTime.now(API_TIME_ZONE));
    }

    public static <T> AppResponse<T> failure(ApiError error) {
        return new AppResponse<>(false, null, Objects.requireNonNull(error), OffsetDateTime.now(API_TIME_ZONE));
    }
}
