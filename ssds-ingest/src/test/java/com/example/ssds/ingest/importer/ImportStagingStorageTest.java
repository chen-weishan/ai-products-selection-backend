package com.example.ssds.ingest.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ImportStagingStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndDeletesMappingSidecarByBatchId() {
        ImportStagingStorage storage = new ImportStagingStorage(
                temporaryDirectory.toString(), DataSize.ofMegabytes(50), Duration.ofHours(24));
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("訂單日期", "orderDate");
        mappings.put("品名", "productName");

        storage.saveMapping(42L, mappings);

        assertThat(storage.loadMapping(42L)).containsExactlyEntriesOf(mappings);
        storage.deleteForBatch(42L);
        assertThat(temporaryDirectory.resolve("42.mapping.json")).doesNotExist();
    }

    @Test
    void cleanupDeletesSourceAndSidecarAndPublishesExpiredBatch() throws Exception {
        ImportStagingStorage storage = new ImportStagingStorage(
                temporaryDirectory.toString(), DataSize.ofMegabytes(50), Duration.ofHours(1));
        List<Object> events = new ArrayList<>();
        storage.setEventPublisher(events::add);
        storage.stageForBatch(7L, "sales.csv", new ByteArrayInputStream(
                "日期,品名\n2026-09-08,奶茶".getBytes(StandardCharsets.UTF_8)));
        storage.saveMapping(7L, Map.of("日期", "orderDate"));
        Files.setLastModifiedTime(temporaryDirectory.resolve("7.csv"),
                FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        storage.cleanupExpired();

        assertThat(temporaryDirectory.resolve("7.csv")).doesNotExist();
        assertThat(temporaryDirectory.resolve("7.mapping.json")).doesNotExist();
        assertThat(events).singleElement().isEqualTo(new ImportArtifactsExpiredEvent(java.util.Set.of(7L)));
    }
}
