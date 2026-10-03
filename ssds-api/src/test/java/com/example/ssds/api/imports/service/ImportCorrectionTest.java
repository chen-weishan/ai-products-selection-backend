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
    @Test
    void salesUseCompletionEventWhileOnlyReviewsEnterTheV31Queue() {
        var dao = mock(BulkImportDao.class);
        var batches = mock(ImportBatchRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        var integrity = mock(com.example.ssds.infra.dao.ImportIntegrityDao.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(batches.findByIdForUpdate(1L)).thenReturn(Optional.of(
                ImportBatch.builder().id(1L).status(TaskStatus.RUNNING).build()));
        when(dao.batchInsertSalesRecords(anyList())).thenReturn(1);
        when(dao.batchInsertReviews(anyList())).thenReturn(1);

        var chunk = new ImportWriteChunk();
        chunk.sales.add(new BulkImportDao.SalesRow(
                LocalDate.now(), 10L, "銷售品項", 1L, BigDecimal.TEN, 1, null, null, 1L));
        chunk.reviews.add(new BulkImportDao.ReviewRow(
                20L, "SHOP", "評論", BigDecimal.ONE, LocalDate.now(), "hash"));

        var limits = mock(ImportDatabaseLimits.class);
        when(limits.seconds()).thenReturn(30);
        var writer = new ImportChunkWriter(
                dao, batches, mock(AudienceSegmentRepository.class), manager, limits);
        org.springframework.test.util.ReflectionTestUtils.setField(writer, "integrity", integrity);

        writer.write(1L, chunk);

        verify(integrity).enqueue(eq(1L), argThat(ids ->
                ids.size() == 1 && ids.contains(20L) && !ids.contains(10L)));
    }

    @Test void unprocessedDownloadUsesCommittedRowsAndNeverIncludesProcessedData() throws Exception {
        var batches=mock(ImportBatchRepository.class);
        var batch=ImportBatch.builder().id(1L).dataType(ImportDataType.SALES).fileName("sales.csv")
            .totalRows(5).successRows(1).failRows(1).skippedRows(1).status(TaskStatus.FAILED).build();
        when(batches.findById(1L)).thenReturn(Optional.of(batch));
        var query=new ImportBatchQueryService(batches,mock(ImportErrorRepository.class));
        var storage=mock(com.example.ssds.ingest.importer.ImportStagingStorage.class);
        var scanner=mock(com.example.ssds.ingest.importer.ImportFileScanner.class);
        var validator=mock(ImportPreviewService.class);
        var path=java.nio.file.Path.of("sales.csv");
        when(storage.findForBatch(1L)).thenReturn(new com.example.ssds.ingest.importer.StagedImportFile("x",path,10,java.time.Instant.now()));
        when(storage.loadMapping(1L)).thenReturn(Map.of("商品","productName"));
        when(validator.validateMappings(any(),any(),any())).thenReturn(Map.of("productName",0));
        doAnswer(invocation->{
            com.example.ssds.ingest.importer.ImportSheetHandler handler=invocation.getArgument(2);
            handler.onHeaders(List.of("商品"));
            // Source row numbers may have gaps: checkpoints count data rows, not sheet numbers.
            for(int i=1;i<=5;i++) handler.onRow(i*2,List.of("商品"+i));
            return null;
        }).when(scanner).scan(eq(path),eq("sales.csv"),any());
        org.springframework.test.util.ReflectionTestUtils.setField(query,"stagingStorage",storage);
        org.springframework.test.util.ReflectionTestUtils.setField(query,"scanner",scanner);
        org.springframework.test.util.ReflectionTestUtils.setField(query,"validation",validator);
        String csv=new String(query.unprocessedCsv(1L),StandardCharsets.UTF_8);
        try(var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(new StringReader(csv.substring(1)))) {
            assertThat(parser.getRecords()).extracting(r->r.get("productName")).containsExactly("商品4","商品5");
            assertThat(parser.getHeaderNames()).doesNotContain("_import_errors");
        }
        batch.setStatus(TaskStatus.RUNNING);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->query.unprocessedCsv(1L))
            .isInstanceOf(com.example.ssds.api.common.error.BusinessException.class);
    }

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
    void batchFailureIsVisibleButNeverBecomesAReimportDataRow() throws Exception {
        var batches = mock(ImportBatchRepository.class);
        var errors = mock(ImportErrorRepository.class);
        when(batches.findById(1L)).thenReturn(Optional.of(ImportBatch.builder()
                .id(1L).dataType(ImportDataType.SALES).totalRows(3)
                .failRows(3).status(TaskStatus.FAILED).build()));
        var batchError = ImportError.builder().rowNumber(0)
                .errorMessage("匯入工作超過允許執行時間 30m").build();
        when(errors.findFirstByBatchIdAndRowNumberOrderByIdDesc(1L, 0))
                .thenReturn(Optional.of(batchError));
        when(errors.findByBatchIdOrderByRowNumberAsc(1L)).thenReturn(List.of(batchError));

        var query=new ImportBatchQueryService(batches, errors);
        org.springframework.test.util.ReflectionTestUtils.setField(query,"integrity",mock(com.example.ssds.infra.dao.ImportIntegrityDao.class));
        var response = query.get(1L);

        assertThat(response.failureReason()).contains("逾時");
        assertThat(response.hasCorrectableErrors()).isFalse();
        String csv = new String(new ImportBatchQueryService(batches, errors).errorCsv(1L), StandardCharsets.UTF_8);
        try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new StringReader(csv.substring(1)))) {
            assertThat(parser.getRecords()).isEmpty();
        }
        verify(errors).existsByBatchIdAndRowNumberGreaterThanAndRawRowIsNotNull(1L, 0);
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
        var writer=new ImportChunkWriter(dao, batches, mock(AudienceSegmentRepository.class), manager, limits);
        org.springframework.test.util.ReflectionTestUtils.setField(writer,"integrity",mock(com.example.ssds.infra.dao.ImportIntegrityDao.class));
        writer.write(1L,chunk);
        assertThat(batch.getSuccessRows()).isEqualTo(2);
        assertThat(batch.getFailRows()).isEqualTo(1);
        verify(manager, times(2)).rollback(any());
        verify(dao).batchInsertImportErrors(argThat(rows -> rows.size() == 1 && rows.getFirst().rowNumber() == 3));
    }
}
