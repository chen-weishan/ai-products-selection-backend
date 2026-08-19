package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 商品評論（規格書 §7.2 product_review）。
 *
 * <p>唯一鍵 (product_id, content_hash) 讓重複匯入直接被資料庫擋下，
 * 不需要「先 SELECT 再 INSERT」——後者在批次匯入時既慢又有競態。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_review")
public class ProductReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** 來源平台或匯入批次識別。 */
    @Column(nullable = false, length = 50)
    private String source;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 原始星等，來源未提供時為 null（不以 0 代替，0 會被誤讀為極差評）。 */
    @Column(precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "reviewed_at")
    private LocalDate reviewedAt;

    /** SHA-256(content)，16 進位小寫共 64 字元。 */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 1:1 分析結果，尚未分析時為 null。 */
    @OneToOne(mappedBy = "review", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private ReviewAnalysis analysis;
}
