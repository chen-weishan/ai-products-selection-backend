package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.ssds.api.imports.dto.*;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.dao.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.example.ssds.ingest.importer.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ImportBusinessRulesTest {
    ImportIntegrityDao integrity=mock(ImportIntegrityDao.class);
    ImportPreviewService validator() {
        var service=new ImportPreviewService(mock(ImportBatchRepository.class),mock(ImportStagingStorage.class),
            mock(ImportFileScanner.class),new ImportFieldRegistry(),mock(ProductRepository.class),mock(CategoryRepository.class),
            mock(SupplierRepository.class),mock(AudienceSegmentRepository.class),mock(ProductReviewRepository.class),mock(ImportTransactionExecutor.class));
        ReflectionTestUtils.setField(service,"integrity",integrity);return service;
    }
    ImportPreviewService.ReferenceCatalog refs() {
        return new ImportPreviewService.ReferenceCatalog(List.of(),Map.of("零食",List.of(Category.builder().id(1L).name("零食").build()),
            "飲料",List.of(Category.builder().id(2L).name("飲料").build())),Map.of(),Map.of(),Set.of());
    }
    Map<String,String> sale(String order,String line) {
        return new LinkedHashMap<>(Map.of("sourceSystem","WEB","orderNo",order,"lineNo",line,
            "orderDate","2026-09-23","productName","奶茶","price","100","qty","2"));
    }
    Map<String,String> audience(String category,String code,String share) {
        return new LinkedHashMap<>(Map.of("category",category,"audienceCode",code,"share",share,"name",code,"priceMin","0","priceMax","100"));
    }
    @Test void distinctOrdersArePreservedAndSameIdentifierChangesAreConflicts() {
        var service=validator();var seen=new HashMap<String,String>();var issues=new ArrayList<ImportPreviewIssue>();
        service.checkSales(sale("A","1"),refs(),seen,issues);service.checkSales(sale("B","1"),refs(),seen,issues);
        assertThat(issues).isEmpty();
        service.checkSales(sale("A","1"),refs(),seen,issues);
        assertThat(issues).singleElement().extracting(ImportPreviewIssue::type).isEqualTo("DUPLICATE");
        issues.clear();var changed=sale("A","1");changed.put("qty","3");
        service.checkSales(changed,refs(),seen,issues);
        assertThat(issues).singleElement().extracting(ImportPreviewIssue::type).isEqualTo("ERROR");
    }
    @Test void databaseConflictIsNotReclassifiedAsDuplicateOnSecondAttempt() {
        var service=validator();var values=sale("A","1");var seen=new HashMap<String,String>();
        when(integrity.existingPayload(anyString())).thenReturn("other-payload");
        for(int i=0;i<2;i++){var issues=new ArrayList<ImportPreviewIssue>();service.checkSales(values,refs(),seen,issues);
            assertThat(issues).singleElement().extracting(ImportPreviewIssue::type).isEqualTo("ERROR");}
    }
    @Test void summaryKeyIncludesChannelAndDimensionsButNotMutableQuantity() {
        var first=sale("A","1");first.put("salesKind","SUMMARY");first.remove("orderNo");first.remove("lineNo");first.put("channel","官網");
        var changed=new LinkedHashMap<>(first);changed.put("qty","85");
        assertThat(SalesImportIdentity.key(first,1L)).isEqualTo(SalesImportIdentity.key(changed,1L));
        assertThat(SalesImportIdentity.payload(first,1L)).isNotEqualTo(SalesImportIdentity.payload(changed,1L));
        changed.put("summaryDimension","門市一");
        assertThat(SalesImportIdentity.key(first,1L)).isNotEqualTo(SalesImportIdentity.key(changed,1L));
    }
    @Test void identifiersMustBeCompleteAndOrderNumberIsAllowedOnlyForSales() {
        var partial=sale("A","1");partial.remove("lineNo");
        assertThat(validator().validateRow(ImportDataType.SALES,partial,refs())).anyMatch(i->"sourceSystem".equals(i.field()));
        var registry=new ImportFieldRegistry();
        assertThat(registry.isPersonalDataHeader(ImportDataType.SALES,"訂單編號")).isFalse();
        assertThat(registry.isPersonalDataHeader(ImportDataType.AUDIENCE,"訂單編號")).isTrue();
    }
    @Test void sameAudienceCanBelongToSeveralCategoriesButTotalsMustBeOne() {
        var validator=validator();var plan=new AudienceImportPlan(validator,refs());
        var a=audience("零食","MAIN","0.8");var b=audience("零食","PREMIUM","0.8");var c=audience("飲料","MAIN","1.000");
        List.of(a,b,c).forEach(plan::accept);plan.finish();
        for(var row:List.of(a,b)){var issues=new ArrayList<ImportPreviewIssue>();plan.addIssue(row,issues);assertThat(issues).anyMatch(i->i.message().contains("1.6"));}
        var issues=new ArrayList<ImportPreviewIssue>();plan.addIssue(c,issues);assertThat(issues).isEmpty();
    }
    @Test void conflictingMasterOrBadMemberRejectsTheEntireCategory() {
        var validator=validator();var plan=new AudienceImportPlan(validator,refs());
        var a=audience("零食","MAIN","0.6");var b=audience("零食","PREMIUM","0.4");var c=audience("飲料","MAIN","1");c.put("priceMax","200");
        List.of(a,b,c).forEach(plan::accept);plan.finish();
        for(var row:List.of(a,b,c)){var issues=new ArrayList<ImportPreviewIssue>();plan.addIssue(row,issues);assertThat(issues).isNotEmpty();}
    }
    @Test void existingMasterRequiresExplicitUpdateOnlyWhenContentDiffers() {
        var refs=refs();var existing=AudienceSegment.builder().audienceCode("MAIN").name("MAIN").priceMin(BigDecimal.ZERO).priceMax(new BigDecimal("100")).build();
        refs=new ImportPreviewService.ReferenceCatalog(refs.products(),refs.categories(),refs.suppliers(),Map.of("main",existing),Set.of());
        var row=audience("零食","MAIN","1");assertThat(validator().validateRow(ImportDataType.AUDIENCE,row,refs)).isEmpty();
        row.put("priceMax","200");assertThat(validator().validateRow(ImportDataType.AUDIENCE,row,refs)).anyMatch(i->"masterAction".equals(i.field()));
        row.put("masterAction","UPDATE");assertThat(validator().validateRow(ImportDataType.AUDIENCE,row,refs)).isEmpty();
    }
    @Test void rowRetryCheckpointsIncludeSkippedRowsInSourceOrder() {
        var chunk=new ImportWriteChunk();chunk.expectedProcessedRows=500;
        chunk.skippedRows=1;chunk.skippedSourceRows.add(502);
        chunk.failedRows=1;chunk.errors.add(new BulkImportDao.ErrorRow(1L,503,null,"bad","{}"));
        var rows=chunk.individualRows();assertThat(rows).hasSize(2);
        assertThat(rows.get(0).expectedProcessedRows).isEqualTo(500);assertThat(rows.get(0).skippedRows).isEqualTo(1);
        assertThat(rows.get(1).expectedProcessedRows).isEqualTo(501);
    }
}
