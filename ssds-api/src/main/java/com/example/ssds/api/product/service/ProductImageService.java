package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.product.dto.ProductImageOrderRequest;
import com.example.ssds.api.product.dto.ProductImageResponse;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductImage;
import com.example.ssds.infra.repository.ProductImageRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** FR-03 品項圖片的查詢、上傳、刪除與排序。 */
@Service
@Transactional
public class ProductImageService {

    private static final int MAX_IMAGE_COUNT = 5;

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ProductImageStorage storage;

    public ProductImageService(
            ProductRepository productRepository,
            ProductImageRepository imageRepository,
            ProductImageStorage storage
    ) {
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public List<ProductImageResponse> getImages(Long productId) {
        findProduct(productId);
        return imageRepository.findByProductIdOrderBySortOrderAsc(productId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductImageContent getContent(Long productId, Long imageId) {
        findProduct(productId);
        ProductImage image = findImage(productId, imageId);
        return storage.read(image.getFilePath());
    }

    public ProductImageResponse upload(Long productId, MultipartFile file) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> notFound("找不到指定的品項：" + productId));
        long currentCount = imageRepository.countByProductId(productId);
        if (currentCount >= MAX_IMAGE_COUNT) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "圖片驗證失敗",
                    List.of(new FieldError("file", "每個品項最多上傳 5 張圖片"))
            );
        }

        String storedPath = storage.store(productId, file);
        try {
            ProductImage saved = imageRepository.saveAndFlush(
                    ProductImage.builder()
                            .product(product)
                            .filePath(storedPath)
                            .sortOrder((int) currentCount)
                            .build()
            );
            return toResponse(saved);
        } catch (RuntimeException exception) {
            storage.delete(storedPath);
            throw exception;
        }
    }

    public void delete(Long productId, Long imageId) {
        findProduct(productId);
        ProductImage image = findImage(productId, imageId);
        imageRepository.delete(image);
        imageRepository.flush();
        storage.delete(image.getFilePath());

        List<ProductImage> remaining = imageRepository
                .findByProductIdOrderBySortOrderAsc(productId);
        for (int index = 0; index < remaining.size(); index++) {
            remaining.get(index).setSortOrder(index);
        }
        imageRepository.saveAllAndFlush(remaining);
    }

    public List<ProductImageResponse> reorder(
            Long productId,
            ProductImageOrderRequest request
    ) {
        findProduct(productId);
        List<ProductImage> images = imageRepository
                .findByProductIdOrderBySortOrderAsc(productId);
        List<Long> requestedIds = request.imageIds();
        Set<Long> uniqueIds = new LinkedHashSet<>(requestedIds);
        Set<Long> existingIds = images.stream()
                .map(ProductImage::getId)
                .collect(java.util.stream.Collectors.toSet());

        if (uniqueIds.size() != requestedIds.size()
                || !uniqueIds.equals(existingIds)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "圖片順序驗證失敗",
                    List.of(new FieldError(
                            "imageIds",
                            "必須提供該品項全部且不重複的圖片 ID"
                    ))
            );
        }

        Map<Long, ProductImage> byId = new LinkedHashMap<>();
        images.forEach(image -> byId.put(image.getId(), image));
        for (int index = 0; index < requestedIds.size(); index++) {
            byId.get(requestedIds.get(index)).setSortOrder(index);
        }
        imageRepository.saveAllAndFlush(images);

        return requestedIds.stream()
                .map(byId::get)
                .map(this::toResponse)
                .toList();
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> notFound("找不到指定的品項：" + productId));
    }

    private ProductImage findImage(Long productId, Long imageId) {
        return imageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> notFound("找不到指定的品項圖片：" + imageId));
    }

    private ProductImageResponse toResponse(ProductImage image) {
        Long productId = image.getProduct().getId();
        return new ProductImageResponse(
                image.getId(),
                productId,
                "/products/" + productId + "/images/" + image.getId() + "/content",
                image.getSortOrder()
        );
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
