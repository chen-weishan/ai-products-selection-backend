package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.dao.BulkImportDao;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.entity.ImportError;
import com.example.ssds.infra.repository.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ImportCorrectionTest {
    @Test void staleCheckpointCannotWriteAnyRows() {
        var dao = mock(BulkImportDao.class);
        var batches = mock(ImportBatchRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(batches.findByIdForUpdate(1L)).thenReturn(Optional.of(
                ImportBatch.builder().id(1L).successRows(500).status(TaskStatus.RUNNING).build()));
        var limits = mock(ImportDatabaseLimits.class);
        when(limits.seconds()).thenReturn(30);
        var writer = new ImportChunkWriter(dao, batches, mock(AudienceSegmentRepository.class), manager, limits);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(1L, new ImportWriteChunk()))
                .isInstanceOf(StaleImportCheckpointException.class);
        verifyNoInteractions(dao);
        verify(manager).rollback(any());
    }

    @Test
    void errorDownloadExpandsDataAndCombinesErrorsForOneSourceRow() throws Exception {
        var batches = mock(ImportBatchRepository.class);
        var errors = mock(ImportErrorRepository.class);
        when(batches.findById(1L)).thenReturn(Optional.of(ImportBatch.builder()
                .id(1L).dataType(ImportDataType.SALES).build()));
        String raw = "{\"productName\":\"奶茶,大杯\",\"price\":\"bad\",\"qty\":\"-1\"}";
        when(errors.findByBatchIdOrderByRowNumberAsc(1L)).thenReturn(List.of(
                ImportError.builder().rowNumber(2).rawRow(raw).columnName("price").errorMessage("格式錯誤").build(),
                ImportError.builder().rowNumber(2).rawRow(raw).columnName("qty").errorMessage("數量錯誤").build()));
        String text = new String(new ImportBatchQueryService(batches, errors).errorCsv(1L), StandardCharsets.UTF_8);
        try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new StringReader(text.substring(1)))) {
            var records = parser.getRecords();
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().get("productName")).isEqualTo("奶茶,大杯");
            assertThat(records.getFirst().get("_import_errors")).contains("price", "qty");
        }
    }

    @Test
    void rollsBackBatchThenRetriesInSourceOrderAndRecordsRejectedRow() {
        var dao = mock(BulkImportDao.class);
        var batches = mock(ImportBatchRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        var batch = ImportBatch.builder().id(1L).totalRows(3).status(TaskStatus.RUNNING).build();
        when(batches.findByIdForUpdate(1L)).thenReturn(Optional.of(batch));
        when(dao.batchInsertSalesRecords(anyList())).thenAnswer(invocation -> {
            List<BulkImportDao.SalesRow> rows = invocation.getArgument(0);
            if (rows.size() > 1 || rows.getFirst().qty() == 2)
                throw new DataIntegrityViolationException("constraint");
            return 1;
        });
        var chunk = new ImportWriteChunk();
        for (int i = 1; i <= 3; i++) {
            chunk.sales.add(new BulkImportDao.SalesRow(LocalDate.now(), null, "奶茶", null,
                    BigDecimal.ONE, i, null, null, 1L));
            chunk.sources.add(new BulkImportDao.ErrorRow(1L, i + 1, null, "", "{\"qty\":\"" + i + "\"}"));
        }
        var limits = mock(ImportDatabaseLimits.class);
        when(limits.seconds()).thenReturn(30);
        new ImportChunkWriter(dao, batches, mock(AudienceSegmentRepository.class), manager, limits).write(1L, chunk);
        assertThat(batch.getSuccessRows()).isEqualTo(2);
        assertThat(batch.getFailRows()).isEqualTo(1);
        verify(manager, times(2)).rollback(any());
        verify(dao).batchInsertImportErrors(argThat(rows -> rows.size() == 1 && rows.getFirst().rowNumber() == 3));
    }
}
