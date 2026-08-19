package com.example.ssds.infra.entity;

import com.example.ssds.infra.entity.id.ItemFestivalAffinityId;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

/**
 * 品項與節慶的關聯度（規格書 §7.2 item_festival_affinity、FR-17-1）。
 *
 * <p>節慶因子 = 關聯度 × 時間窗權重(日期, 節慶日, 品類前置天數)。
 * 節慶不是固定的加分欄位而是<b>時間窗函數</b>：月餅在七月分數應該高
 * （備貨期），在十月應該歸零。
 *
 * <p>{@link #festivalCode} 刻意不外鍵到 festival_calendar：關聯度是跨年度的
 * 品項屬性，而 festival_calendar 逐年一列，接上去會變成每年都要重建一份關聯度。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "item_festival_affinity")
@IdClass(ItemFestivalAffinityId.class)
public class ItemFestivalAffinity {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Id
    @Column(name = "festival_code", nullable = false, length = 32)
    private String festivalCode;

    /** 0–1。初期人工建立，累積後由 AI 建議、人審核。 */
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal affinity;
}
