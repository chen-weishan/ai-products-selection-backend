package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 品項圖片的本機檔案儲存；資料庫只保存可攜的相對路徑。 */
@Component
public class ProductImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ProductImageStorage.class);
    static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    private static final String PUBLIC_PREFIX = "/uploads/product/";

    private final Path storageRoot;

    public ProductImageStorage(
            @Value("${ssds.product-image.storage-path:./uploads/product}")
            String storagePath
    ) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    public String store(Long productId, MultipartFile file) {
        byte[] bytes = readAndValidate(file);
        String extension = detectExtension(bytes);
        Path productDirectory = storageRoot.resolve(productId.toString()).normalize();
        Path target = productDirectory
                .resolve(UUID.randomUUID() + extension)
                .normalize();
        ensureInsideStorage(target);

        try {
            Files.createDirectories(productDirectory);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw storageException("圖片儲存失敗", exception);
        }

        return PUBLIC_PREFIX
                + productId
                + "/"
                + target.getFileName();
    }

    public ProductImageContent read(String storedPath) {
        Path path = resolveStoredPath(storedPath);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "找不到指定的圖片檔案"
            );
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            return new ProductImageContent(
                    bytes,
                    mediaType(detectExtension(bytes))
            );
        } catch (IOException exception) {
            throw storageException("圖片讀取失敗", exception);
        }
    }

    public void delete(String storedPath) {
        Path path = resolveStoredPath(storedPath);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw storageException("圖片刪除失敗", exception);
        }
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw validationException("圖片檔案不可為空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw validationException("圖片大小不可超過 2MB");
        }

        String contentType = file.getContentType();
        if (!MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)
                && !MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType)) {
            throw validationException("圖片格式只允許 jpg 或 png");
        }

        try {
            byte[] bytes = file.getBytes();
            detectExtension(bytes);
            return bytes;
        } catch (IOException exception) {
            throw storageException("圖片讀取失敗", exception);
        }
    }

    private String detectExtension(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return ".png";
        }
        throw validationException("圖片內容不是有效的 jpg 或 png");
    }

    private MediaType mediaType(String extension) {
        return ".png".equals(extension)
                ? MediaType.IMAGE_PNG
                : MediaType.IMAGE_JPEG;
    }

    private Path resolveStoredPath(String storedPath) {
        if (storedPath == null || !storedPath.startsWith(PUBLIC_PREFIX)) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "圖片儲存路徑格式不正確"
            );
        }
        Path path = storageRoot
                .resolve(storedPath.substring(PUBLIC_PREFIX.length()))
                .normalize();
        ensureInsideStorage(path);
        return path;
    }

    private void ensureInsideStorage(Path path) {
        if (!path.startsWith(storageRoot)) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "圖片儲存路徑超出允許範圍"
            );
        }
    }

    private BusinessException validationException(String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "圖片驗證失敗",
                List.of(new FieldError("file", message))
        );
    }

    private BusinessException storageException(
            String message,
            IOException cause
    ) {
        log.error(message, cause);
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message);
    }
}
