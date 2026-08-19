package com.example.ssds.api.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message == null || message.isBlank()
                ? Objects.requireNonNull(errorCode).defaultMessage()
                : message);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
