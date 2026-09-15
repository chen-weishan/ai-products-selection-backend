package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductImageOrderRequest;
import com.example.ssds.api.product.dto.ProductImageResponse;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductImage;
import com.example.ssds.infra.repository.ProductImageRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class ProductImageServiceTest {

    private ProductRepository productRepository;
    private ProductImageRepository imageRepository;
    private ProductImageStorage storage;
    private ProductImageService service;
    private Product product;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        imageRepository = mock(ProductImageRepository.class);
        storage = mock(ProductImageStorage.class);
        service = new ProductImageService(
                productRepository,
                imageRepository,
                storage
        );
        product = Product.builder().id(50L).name("測試品項").build();
    }

    @Test
    void uploadStoresImageWithNextSortOrder() {
        MultipartFile file = mock(MultipartFile.class);
        when(productRepository.findByIdForUpdate(50L))
                .thenReturn(Optional.of(product));
        when(imageRepository.countByProductId(50L)).thenReturn(2L);
        when(storage.store(50L, file))
                .thenReturn("/uploads/product/50/test.jpg");
        when(imageRepository.saveAndFlush(any(ProductImage.class)))
                .thenAnswer(invocation -> {
                    ProductImage image = invocation.getArgument(0);
                    image.setId(10L);
                    return image;
                });

        ProductImageResponse response = service.upload(50L, file);

        assertEquals(10L, response.id());
        assertEquals(2, response.sortOrder());
        assertEquals(
                "/products/50/images/10/content",
                response.contentPath()
        );
    }

    @Test
    void sixthImageIsRejectedBeforeWritingFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(productRepository.findByIdForUpdate(50L))
                .thenReturn(Optional.of(product));
        when(imageRepository.countByProductId(50L)).thenReturn(5L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(50L, file)
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verify(storage, never()).store(any(), any());
    }

    @Test
    void reorderRequiresEveryImageExactlyOnce() {
        ProductImage first = image(1L, 0);
        ProductImage second = image(2L, 1);
        when(productRepository.findById(50L)).thenReturn(Optional.of(product));
        when(imageRepository.findByProductIdOrderBySortOrderAsc(50L))
                .thenReturn(List.of(first, second));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.reorder(
                        50L,
                        new ProductImageOrderRequest(List.of(1L, 1L))
                )
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verify(imageRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void reorderUpdatesSortOrderAndReturnsRequestedOrder() {
        ProductImage first = image(1L, 0);
        ProductImage second = image(2L, 1);
        when(productRepository.findById(50L)).thenReturn(Optional.of(product));
        when(imageRepository.findByProductIdOrderBySortOrderAsc(50L))
                .thenReturn(List.of(first, second));

        List<ProductImageResponse> response = service.reorder(
                50L,
                new ProductImageOrderRequest(List.of(2L, 1L))
        );

        assertEquals(List.of(2L, 1L), response.stream()
                .map(ProductImageResponse::id)
                .toList());
        assertEquals(0, second.getSortOrder());
        assertEquals(1, first.getSortOrder());
    }

    private ProductImage image(Long id, int sortOrder) {
        return ProductImage.builder()
                .id(id)
                .product(product)
                .filePath("/uploads/product/50/" + id + ".jpg")
                .sortOrder(sortOrder)
                .build();
    }
}
