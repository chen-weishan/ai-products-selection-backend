package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.*;

/**
 * 歷史銷售紀錄（規格書 §7.2 sales_record）。
 *
 * <p>conversion 因子（§5.2.1 歷史轉換率）的資料來源。
 * 轉換率 = qty / impression，{@link #impression} 為 null 時該因子標示
 * 「無資料」而非以 0 計算（§5.7）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sales_record")
public class SalesRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    /** 比對不到品項時為 null，仍保留 {@link #productNameRaw} 供後續人工對應。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "product_name_raw", nullable = false, length = 150)
    private String productNameRaw;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int qty;

    /** 曝光數，來源未提供時為 null。 */
    private Integer impression;

    /** 對應 audience_segment.audience_code（§7.2.11）。供 PRICE_FIT 因子計算。 */
    @Column(name = "audience_code", length = 24)
    private String audienceCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_batch_id")
    private ImportBatch importBatch;
}
