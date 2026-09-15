package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class ProductImageStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void validJpegCanBeStoredReadAndDeleted() throws IOException {
        ProductImageStorage storage = new ProductImageStorage(
                tempDirectory.toString()
        );
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                jpeg
        );

        String storedPath = storage.store(50L, file);
        ProductImageContent content = storage.read(storedPath);

        assertTrue(storedPath.startsWith("/uploads/product/50/"));
        assertArrayEquals(jpeg, content.bytes());
        assertEquals(MediaType.IMAGE_JPEG, content.mediaType());

        storage.delete(storedPath);
        try (var files = Files.list(tempDirectory.resolve("50"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void fakeImageContentIsRejectedEvenWithAllowedMimeType() {
        ProductImageStorage storage = new ProductImageStorage(
                tempDirectory.toString()
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "fake.png",
                MediaType.IMAGE_PNG_VALUE,
                "not-an-image".getBytes()
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> storage.store(50L, file)
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void imageLargerThanTwoMegabytesIsRejected() {
        ProductImageStorage storage = new ProductImageStorage(
                tempDirectory.toString()
        );
        byte[] oversized = new byte[(int) ProductImageStorage.MAX_FILE_SIZE + 1];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                oversized
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> storage.store(50L, file)
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertTrue(exception.getFieldErrors().getFirst().message().contains("2MB"));
    }
}
