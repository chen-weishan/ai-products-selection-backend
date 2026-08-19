package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ProductImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 品項圖片（規格書 §7.2 product_image）。 */
@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId);

    void deleteByProductId(Long productId);
}
