package com.example.ssds.api.exception;

import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.api.common.response.AppResponse;
import com.example.ssds.api.common.response.FieldError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<AppResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.errorCode();
        return errorResponse(errorCode.httpStatus(), errorCode, exception.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AppResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(
                        error.getField(),
                        error.getDefaultMessage() == null ? "欄位值不正確" : error.getDefaultMessage()
                ))
                .toList();

        return errorResponse(
                ErrorCode.VALIDATION_ERROR.httpStatus(),
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                fieldErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<AppResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        List<FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .toList();

        return errorResponse(
                ErrorCode.VALIDATION_ERROR.httpStatus(),
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AppResponse<Void>> handleUnreadableRequest(
            HttpMessageNotReadableException exception
    ) {
        return errorResponse(
                ErrorCode.VALIDATION_ERROR.httpStatus(),
                ErrorCode.VALIDATION_ERROR,
                "請求內容格式不正確",
                null
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AppResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return errorResponse(
                ErrorCode.INTERNAL_ERROR.httpStatus(),
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(),
                null
        );
    }

    private ResponseEntity<AppResponse<Void>> errorResponse(
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            List<FieldError> fieldErrors
    ) {
        ApiError error = new ApiError(errorCode.name(), message, fieldErrors);
        return ResponseEntity.status(status).body(AppResponse.failure(error));
    }
}
