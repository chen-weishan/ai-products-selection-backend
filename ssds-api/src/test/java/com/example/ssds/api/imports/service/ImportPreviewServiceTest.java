package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.imports.dto.ImportPreviewRequest;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.AudienceSegmentRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.ingest.importer.ImportFieldRegistry;
import com.example.ssds.ingest.importer.ImportFileScanner;
import com.example.ssds.ingest.importer.ImportSheetHandler;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import com.example.ssds.ingest.importer.StagedImportFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImportPreviewServiceTest {

    @Test
    void validatesAllRowsAndSeparatesValidErrorAndDuplicateCounts() throws Exception {
        ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
        ImportStagingStorage storage = mock(ImportStagingStorage.class);
        ImportFileScanner scanner = mock(ImportFileScanner.class);
        ProductRepository products = mock(ProductRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        SupplierRepository suppliers = mock(SupplierRepository.class);
        AudienceSegmentRepository audiences = mock(AudienceSegmentRepository.class);
        ProductReviewRepository reviews = mock(ProductReviewRepository.class);
        ImportPreviewService service = new ImportPreviewService(
                batchRepository,
                storage,
                scanner,
                new ImportFieldRegistry(),
                products,
                categories,
                suppliers,
                audiences,
                reviews,
                directTransactions());

        ImportBatch batch = ImportBatch.builder()
                .id(42L)
                .dataType(ImportDataType.SALES)
                .fileName("sales.csv")
                .totalRows(3)
                .async(false)
                .build();
        Path path = Path.of("42.csv");
        List<String> headers = List.of("訂單日期", "品名", "單價", "數量");
        when(batchRepository.findById(42L)).thenReturn(Optional.of(batch));
        when(storage.findForBatch(42L))
                .thenReturn(new StagedImportFile("42", path, 100L, Instant.now()));
        when(products.findAllWithCategory()).thenReturn(List.of());
        when(categories.findAll()).thenReturn(List.of());
        when(suppliers.findAll()).thenReturn(List.of());
        when(audiences.findAll()).thenReturn(List.of());
        doAnswer(invocation -> {
            ImportSheetHandler handler = invocation.getArgument(2);
            handler.onHeaders(headers);
            handler.onRow(2, List.of("2026-09-01", "奶茶", "100", "2"));
            handler.onRow(3, List.of("2026-09-01", "奶茶", "100.00", "2"));
            handler.onRow(4, List.of("2026-09-02", "奶茶", "100", "-1"));
            return null;
        }).when(scanner).scan(eq(path), eq("sales.csv"), any(ImportSheetHandler.class));
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("訂單日期", "orderDate");
        mappings.put("品名", "productName");
        mappings.put("單價", "price");
        mappings.put("數量", "qty");

        var response = service.preview(42L, new ImportPreviewRequest(mappings));

        assertThat(response.totalRows()).isEqualTo(3);
        assertThat(response.validRows()).isEqualTo(1);
        assertThat(response.duplicateRows()).isEqualTo(1);
        assertThat(response.errorRows()).isEqualTo(1);
        assertThat(response.previewRows()).hasSize(3);

        // Download must include rejected rows beyond the first 20, with no raw PII columns.
        doAnswer(invocation -> {
            ImportSheetHandler handler = invocation.getArgument(2);
            handler.onHeaders(List.of("訂單日期", "品名", "單價", "數量", "email"));
            for (int i = 0; i < 21; i++) {
                handler.onRow(i + 2, List.of("2026-09-01", "奶茶" + i, "100", "2", "private@example.test"));
            }
            handler.onRow(23, List.of("2026-09-01", "奶茶,\"大杯\"", "100", "-1", "private@example.test"));
            handler.onRow(24, List.of("2026-09-01", "奶茶0", "100", "2", "private@example.test"));
            return null;
        }).when(scanner).scan(eq(path), eq("sales.csv"), any(ImportSheetHandler.class));
        String csv = new String(service.errorCsv(42L, new ImportPreviewRequest(mappings)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff").doesNotContain("private@example.test");
        try (var parser = org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).get()
                .parse(new java.io.StringReader(csv.substring(1)))) {
            var rows = parser.getRecords();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("_import_row_number")).isEqualTo("23");
            assertThat(rows.get(0).get("productName")).isEqualTo("奶茶,\"大杯\"");
            assertThat(rows.get(0).get("_import_errors")).contains("qty");
            assertThat(rows.get(1).get("_import_errors")).contains("重複");
            assertThat(parser.getHeaderNames()).doesNotContain("email");
        }
        org.mockito.Mockito.verify(batchRepository, org.mockito.Mockito.never()).save(any());

        // A changed mapping is validated again rather than reusing stale preview results.
        var invalid = new LinkedHashMap<>(mappings);
        invalid.put("email", "productName");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.errorCsv(42L, new ImportPreviewRequest(invalid)))
                .isInstanceOf(com.example.ssds.api.common.error.BusinessException.class);
    }

    private ImportTransactionExecutor directTransactions() {
        var transactions = mock(ImportTransactionExecutor.class);
        when(transactions.readOnly(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        return transactions;
    }
}
