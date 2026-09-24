package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.ingest.importer.ImportFieldRegistry;
import com.example.ssds.ingest.importer.ImportFileParseException;
import com.example.ssds.ingest.importer.ImportFileScanner;
import com.example.ssds.ingest.importer.ImportHeaderMapper;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import com.example.ssds.ingest.importer.StagedImportFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImportPendingResumeServiceTest {
    @Test
    void rebuildsUploadHeadersForPendingBatch() {
        var batches = mock(ImportBatchRepository.class);
        var storage = mock(ImportStagingStorage.class);
        var scanner = mock(ImportFileScanner.class);
        var transactions = directTransactions();
        var batch = ImportBatch.builder().id(12L).dataType(ImportDataType.SALES)
                .fileName("sales.csv").totalRows(4).status(TaskStatus.PENDING).build();
        Path path = Path.of("12.csv");
        when(batches.findById(12L)).thenReturn(Optional.of(batch));
        when(storage.findForBatch(12L))
                .thenReturn(new StagedImportFile("12", path, 120L, Instant.now()));
        when(scanner.readHeaders(path, "sales.csv"))
                .thenReturn(List.of("訂單日期", "品名", "單價", "數量"));
        when(storage.loadDraftMapping(12L)).thenReturn(java.util.Map.of("品名", "productName"));
        var service = new ImportPendingResumeService(batches, storage, scanner,
                new ImportHeaderMapper(new ImportFieldRegistry()), transactions);

        var result = service.resume(12L);

        assertThat(result.batchId()).isEqualTo(12L);
        assertThat(result.headers()).containsExactly("訂單日期", "品名", "單價", "數量");
        assertThat(result.mappingSuggestions()).hasSize(4);
        assertThat(result.savedMappings()).containsEntry("品名", "productName");
    }

    @Test
    void rejectsCompletedOrExpiredPendingBatch() {
        var batches = mock(ImportBatchRepository.class);
        var storage = mock(ImportStagingStorage.class);
        var scanner = mock(ImportFileScanner.class);
        var service = new ImportPendingResumeService(batches, storage, scanner,
                new ImportHeaderMapper(new ImportFieldRegistry()), directTransactions());
        when(batches.findById(12L)).thenReturn(Optional.of(ImportBatch.builder()
                .id(12L).status(TaskStatus.SUCCEEDED).build()));
        assertThatThrownBy(() -> service.resume(12L)).isInstanceOf(BusinessException.class);

        when(batches.findById(12L)).thenReturn(Optional.of(ImportBatch.builder()
                .id(12L).status(TaskStatus.PENDING).build()));
        when(storage.findForBatch(12L)).thenThrow(new ImportFileParseException("檔案不存在"));
        assertThatThrownBy(() -> service.resume(12L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新上傳");
    }

    private ImportTransactionExecutor directTransactions() {
        var transactions = mock(ImportTransactionExecutor.class);
        when(transactions.readOnly(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        return transactions;
    }
}
