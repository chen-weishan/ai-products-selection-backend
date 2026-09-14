package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.ingest.importer.ImportFileParser;
import com.example.ssds.ingest.importer.ImportParseResult;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import com.example.ssds.ingest.importer.StagedImportFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class ImportUploadServiceTest {

    @Test
    void createsBatchStagesFileAndMarksLargeRowCountAsAsync() throws Exception {
        ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
        AppUserRepository userRepository = mock(AppUserRepository.class);
        ImportStagingStorage storage = mock(ImportStagingStorage.class);
        ImportFileParser parser = mock(ImportFileParser.class);
        ImportUploadService service = new ImportUploadService(
                batchRepository,
                userRepository,
                storage,
                parser,
                DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(50),
                5_000);
        AppUser actor = AppUser.builder().id(7L).email("admin@test.local").build();
        when(userRepository.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        when(batchRepository.saveAndFlush(any(ImportBatch.class))).thenAnswer(invocation -> {
            ImportBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) batch.setId(42L);
            return batch;
        });
        Path path = Path.of("sales.csv");
        when(storage.stageForBatch(eq(42L), eq("sales.csv"), any()))
                .thenReturn(new StagedImportFile("42", path, 100L, Instant.now()));
        when(parser.parse(path, "sales.csv", ImportDataType.SALES))
                .thenReturn(new ImportParseResult(
                        List.of("訂單日期", "品名"), List.of(), List.of(), 5_001));
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.csv", "text/csv", "header\nvalue".getBytes());

        var response = service.upload(ImportDataType.SALES, file, actor.getEmail());

        assertThat(response.batchId()).isEqualTo(42L);
        assertThat(response.totalRows()).isEqualTo(5_001);
        assertThat(response.async()).isTrue();
        verify(storage).stageForBatch(eq(42L), eq("sales.csv"), any());
    }
}
