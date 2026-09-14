package com.example.ssds.ingest.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * FR-09 本機暫存檔策略：隨機 token 命名、限定在 staging root、串流限制 50MB，
 * 並定期清除超過保存期限的檔案。正式部署可把 root 指向持久化 volume。
 */
@Component
public class ImportStagingStorage {

    private static final Logger log = LoggerFactory.getLogger(ImportStagingStorage.class);

    private final Path root;
    private final long hardMaxBytes;
    private final Duration retention;
    private final ObjectMapper objectMapper;
    private ApplicationEventPublisher eventPublisher = event -> {};

    public ImportStagingStorage(
            @Value("${ssds.import.staging-path:./uploads/import-staging}") String storagePath,
            @Value("${ssds.import.hard-max-bytes:50MB}") DataSize hardMaxSize,
            @Value("${ssds.import.staging-retention:24h}") Duration retention
    ) {
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
        this.hardMaxBytes = hardMaxSize.toBytes();
        this.retention = retention;
        this.objectMapper = new ObjectMapper();
    }

    @Autowired
    void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public StagedImportFile stage(String originalFilename, InputStream input) {
        return stageWithToken(UUID.randomUUID().toString(), originalFilename, input);
    }

    /** 讓 preview request 可在服務重啟後仍以 batchId 找回檔案，不依賴記憶體 map。 */
    public StagedImportFile stageForBatch(
            Long batchId,
            String originalFilename,
            InputStream input
    ) {
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 必須是正整數");
        }
        return stageWithToken(batchId.toString(), originalFilename, input);
    }

    public StagedImportFile findForBatch(Long batchId) {
        for (String extension : List.of("csv", "xlsx")) {
            Path path = root.resolve(batchId + "." + extension).normalize();
            ensureInsideRoot(path);
            if (Files.isRegularFile(path)) {
                try {
                    return new StagedImportFile(
                            batchId.toString(),
                            path,
                            Files.size(path),
                            Files.getLastModifiedTime(path).toInstant());
                } catch (IOException exception) {
                    throw new ImportFileParseException("無法讀取匯入暫存檔", exception);
                }
            }
        }
        throw new ImportFileParseException("匯入暫存檔不存在或已逾保存期限");
    }

    public void deleteForBatch(Long batchId) {
        for (String extension : List.of("csv", "xlsx")) {
            Path path = root.resolve(batchId + "." + extension).normalize();
            ensureInsideRoot(path);
            deleteQuietly(path);
        }
        deleteQuietly(mappingPath(batchId));
    }

    /**
     * 原子保存確認匯入時採用的欄位對應。mapping 與來源檔同屬暫存 artifact，
     * 不需要為短生命週期資料擴充 import_batch schema。
     */
    public void saveMapping(Long batchId, Map<String, String> mappings) {
        Path target = mappingPath(batchId);
        Path temporary = root.resolve(batchId + ".mapping.json.tmp").normalize();
        ensureInsideRoot(temporary);
        try {
            Files.createDirectories(root);
            objectMapper.writeValue(temporary.toFile(), mappings);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new ImportFileParseException("欄位對應暫存失敗", exception);
        }
    }

    public Map<String, String> loadMapping(Long batchId) {
        Path path = mappingPath(batchId);
        if (!Files.isRegularFile(path)) {
            throw new ImportFileParseException("匯入欄位對應不存在或已逾保存期限");
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
        } catch (IOException exception) {
            throw new ImportFileParseException("匯入欄位對應無法讀取", exception);
        }
    }

    public Instant mappingSavedAt(Long batchId) {
        try {
            return Files.getLastModifiedTime(mappingPath(batchId)).toInstant();
        } catch (IOException exception) {
            throw new ImportFileParseException("匯入欄位對應不存在或無法讀取", exception);
        }
    }

    private Path mappingPath(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new IllegalArgumentException("batchId 必須是正整數");
        }
        Path path = root.resolve(batchId + ".mapping.json").normalize();
        ensureInsideRoot(path);
        return path;
    }

    private StagedImportFile stageWithToken(
            String token,
            String originalFilename,
            InputStream input
    ) {
        ImportFileFormat format = ImportFileFormat.fromFilename(originalFilename);
        Path target = root.resolve(token + "." + format.name().toLowerCase(Locale.ROOT)).normalize();
        ensureInsideRoot(target);
        long written = 0;
        try {
            Files.createDirectories(root);
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > hardMaxBytes) {
                        throw new ImportFileSizeLimitExceededException(hardMaxBytes);
                    }
                    output.write(buffer, 0, count);
                }
            }
            return new StagedImportFile(token, target, written, Instant.now());
        } catch (ImportFileSizeLimitExceededException exception) {
            deleteQuietly(target);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(target);
            throw new ImportFileParseException("匯入檔案暫存失敗", exception);
        }
    }

    public void delete(StagedImportFile file) {
        ensureInsideRoot(file.path());
        deleteQuietly(file.path());
    }

    @Scheduled(fixedDelayString = "${ssds.import.staging-cleanup-delay:1h}")
    public void cleanupExpired() {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(retention);
        Set<Long> expiredBatches = new HashSet<>();
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (!Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                        return;
                    }
                    String name = path.getFileName().toString();
                    Long batchId = sourceBatchId(name);
                    if (batchId != null) {
                        deleteForBatch(batchId);
                        expiredBatches.add(batchId);
                    } else if (!isMappingWithExistingSource(name)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException exception) {
                    log.warn("無法清除過期匯入暫存檔：{}", path.getFileName(), exception);
                }
            });
        } catch (IOException exception) {
            log.warn("無法掃描匯入暫存目錄", exception);
        }
        if (!expiredBatches.isEmpty()) {
            eventPublisher.publishEvent(new ImportArtifactsExpiredEvent(expiredBatches));
        }
    }

    private Long sourceBatchId(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String extension = lower.endsWith(".csv") ? ".csv"
                : lower.endsWith(".xlsx") ? ".xlsx" : null;
        if (extension == null) return null;
        String stem = fileName.substring(0, fileName.length() - extension.length());
        try {
            long value = Long.parseLong(stem);
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isMappingWithExistingSource(String fileName) {
        if (!fileName.endsWith(".mapping.json")) return false;
        String stem = fileName.substring(0, fileName.length() - ".mapping.json".length());
        return Files.isRegularFile(root.resolve(stem + ".csv"))
                || Files.isRegularFile(root.resolve(stem + ".xlsx"));
    }

    private void ensureInsideRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("暫存路徑超出允許範圍");
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("無法刪除匯入暫存檔：{}", path.getFileName(), exception);
        }
    }
}
