package com.example.ssds.api.imports.service;

import com.example.ssds.infra.dao.BulkImportDao;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 最多 500 個來源列的有界寫入單位，避免大型匯入累積在 persistence context。 */
final class ImportWriteChunk {
    final List<BulkImportDao.SalesRow> sales = new ArrayList<>();
    final List<BulkImportDao.ReviewRow> reviews = new ArrayList<>();
    final List<BulkImportDao.AudienceRow> audiences = new ArrayList<>();
    final List<AudienceMixInput> audienceMixes = new ArrayList<>();
    final List<BulkImportDao.ProductRow> products = new ArrayList<>();
    final List<BulkImportDao.ErrorRow> errors = new ArrayList<>();
    int failedRows;
    int skippedRows;
    final List<Integer> skippedSourceRows=new ArrayList<>();
    final List<SalesIdentity> salesIdentities = new ArrayList<>();
    record SalesIdentity(String key,String payload) {}
    int expectedProcessedRows;
    long deadlineNanos;
    final List<BulkImportDao.ErrorRow> sources = new ArrayList<>();

    List<ImportWriteChunk> individualRows() {
        var result = new java.util.TreeMap<Integer, ImportWriteChunk>();
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i);
            var row = new ImportWriteChunk();
            row.sources.add(source);
            if (!sales.isEmpty()) { row.sales.add(sales.get(i)); if(!salesIdentities.isEmpty()) row.salesIdentities.add(salesIdentities.get(i)); }
            if (!reviews.isEmpty()) row.reviews.add(reviews.get(i));
            if (!products.isEmpty()) row.products.add(products.get(i));
            if (!audiences.isEmpty()) {
                var audience = audiences.get(i);
                row.audiences.add(audience);
                audienceMixes.stream().filter(m -> m.audienceCode().equals(audience.audienceCode()))
                        .forEach(row.audienceMixes::add);
            }
            result.put(source.rowNumber(), row);
        }
        for (var error : errors) {
            var row = result.computeIfAbsent(error.rowNumber(), ignored -> new ImportWriteChunk());
            row.errors.add(error);
            row.failedRows = 1;
        }
        for(int number:skippedSourceRows) {var row=new ImportWriteChunk(); row.skippedRows=1; result.put(number,row);}
        int checkpoint = expectedProcessedRows;
        for (var row : result.values()) {
            row.expectedProcessedRows = checkpoint++;
            row.deadlineNanos = deadlineNanos;
        }
        return new ArrayList<>(result.values());
    }

    int validRows() {
        return sales.size() + reviews.size() + audiences.size() + products.size();
    }

    boolean isEmpty() {
        return validRows() == 0 && failedRows == 0 && skippedRows == 0;
    }

    record AudienceMixInput(String audienceCode, Long categoryId, BigDecimal share) {}
}
