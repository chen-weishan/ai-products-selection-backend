package com.example.ssds.api.common.response;

import com.example.ssds.api.common.error.ApiErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** 規格書 §8.1 統一錯誤回應格式。 */
public record ApiErrorResponse(
        boolean success,
        ErrorBody error,
        OffsetDateTime timestamp) {

    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    /** 建立沒有欄位錯誤明細的錯誤回應。 */
    public static ApiErrorResponse of(ApiErrorCode code, String message) {
        return of(code, message, List.of());
    }

    /** 建立包含欄位錯誤明細的錯誤回應。 */
    public static ApiErrorResponse of(
            ApiErrorCode code, String message, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(
                false,
                new ErrorBody(
                        code.name(),
                        message,
                        fieldErrors == null ? List.of() : List.copyOf(fieldErrors)),
                OffsetDateTime.now(API_ZONE));
    }

    /** 規格書定義的 error 物件。 */
    public record ErrorBody(String code, String message, List<FieldError> fieldErrors) {}

    /** 單一欄位的驗證錯誤。 */
    public record FieldError(String field, String message) {}
}
