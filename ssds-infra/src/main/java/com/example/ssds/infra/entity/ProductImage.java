package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import lombok.*;

/** 品項圖片（規格書 §7.2 product_image）。只存路徑，檔案本體不進資料庫。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_image")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;
}
