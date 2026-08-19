package com.example.ssds.api.common.response;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppResponseTest {

    @Test
    void successContainsDataWithoutError() {
        AppResponse<String> response = AppResponse.success("ok");

        assertTrue(response.success());
        assertEquals("ok", response.data());
        assertNull(response.error());
        assertEquals(ZoneOffset.ofHours(8), response.timestamp().getOffset());
    }

    @Test
    void failureContainsImmutableErrorWithoutData() {
        List<FieldError> mutableErrors = new java.util.ArrayList<>();
        mutableErrors.add(new FieldError("name", "不可空白"));
        ApiError error = new ApiError("VALIDATION_ERROR", "驗證失敗", mutableErrors);

        AppResponse<Void> response = AppResponse.failure(error);
        mutableErrors.clear();

        assertFalse(response.success());
        assertNull(response.data());
        assertEquals("VALIDATION_ERROR", response.error().code());
        assertEquals(1, response.error().fieldErrors().size());
        assertThrows(UnsupportedOperationException.class,
                () -> response.error().fieldErrors().clear());
    }
}
