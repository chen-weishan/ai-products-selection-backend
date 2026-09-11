package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.infra.dao.BulkImportDao;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ProductReviewFileServiceTest {

    private ProductRepository productRepository;
    private ProductReviewRepository reviewRepository;
    private BulkImportDao bulkImportDao;
    private ProductReviewFileService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        reviewRepository = mock(ProductReviewRepository.class);
        bulkImportDao = mock(BulkImportDao.class);
        service = new ProductReviewFileService(
                productRepository,
                reviewRepository,
                bulkImportDao
        );
        when(productRepository.findById(101L))
                .thenReturn(Optional.of(Product.builder().id(101L).build()));
    }

    @Test
    void importsValidCsvAndReportsDuplicates() {
        MockMultipartFile file = csv("""
                content,source,rating,reviewed_at
                \"很好吃，會回購\",官網,4.5,2026-08-30
                包裝完整,商城,,
                """);
        when(bulkImportDao.batchInsertReviews(anyList())).thenReturn(1);
        when(reviewRepository.countByProductId(101L)).thenReturn(12L);

        var response = service.upload(101L, file);

        assertEquals(2, response.acceptedRows());
        assertEquals(1, response.insertedCount());
        assertEquals(1, response.duplicateCount());
        assertEquals(true, response.lowConfidence());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<BulkImportDao.ReviewRow>> captor =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(bulkImportDao).batchInsertReviews(captor.capture());
        assertEquals("很好吃，會回購", captor.getValue().getFirst().content());
        assertEquals(64, captor.getValue().getFirst().contentHash().length());
    }

    @Test
    void returnsExistingReviewSummary() {
        when(reviewRepository.countByProductId(101L)).thenReturn(42L);

        var response = service.summary(101L);

        assertEquals(42L, response.totalReviewCount());
        assertEquals(false, response.lowConfidence());
    }

    @Test
    void usesTwentyReviewsAsTheConfidenceBoundary() {
        when(reviewRepository.countByProductId(101L)).thenReturn(19L, 20L);

        assertEquals(true, service.summary(101L).lowConfidence());
        assertEquals(false, service.summary(101L).lowConfidence());
    }

    @Test
    void rejectsCsvWithoutContentHeader() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(101L, csv("rating,source\n5,官網\n"))
        );

        assertEquals("CSV 必須包含 content（評論內容）欄位", exception.getMessage());
    }

    @Test
    void rejectsRatingOutsideZeroToFive() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(101L, csv("content,rating\n好吃,6\n"))
        );

        assertEquals("評分必須是 0–5，最多一位小數", exception.getMessage());
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile(
                "file",
                "reviews.csv",
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
