package com.example.ssds.api.common.error;

import com.example.ssds.api.common.response.ApiErrorResponse.FieldError;
import java.util.List;

/**
 * Controller 或 application service 可主動拋出的統一 API 例外。
 *
 * <p>此類別屬於 API 邊界，不放入不應依賴 Web 層的 ssds-core。
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final List<FieldError> fieldErrors;

    public ApiException(ApiErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(
            ApiErrorCode code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public ApiErrorCode getCode() {
        return code;
    }

    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
