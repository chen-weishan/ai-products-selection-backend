package com.example.ssds.api.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        List<FieldError> fieldErrors
) {
    public ApiError {
        fieldErrors = fieldErrors == null || fieldErrors.isEmpty()
                ? null
                : List.copyOf(fieldErrors);
    }

    public ApiError(String code, String message) {
        this(code, message, null);
    }
}
