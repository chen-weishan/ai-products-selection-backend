package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 品類前置天數（規格書 §7.2 category_lead_time、FR-17-1）。
 *
 * <p>AC-17-3：同一份資料同時供 FR-17 節慶時間窗與 FR-16 時效落差使用，
 * 只維護一份 —— 兩處各存一份必然會不同步，而不同步的後果是
 * 「節慶算得剛好、尋源卻說來不及」這種自相矛盾的畫面。
 *
 * <p>與 {@link Category} 1:1 共用主鍵。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "category_lead_time")
public class CategoryLeadTime {

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;
}
