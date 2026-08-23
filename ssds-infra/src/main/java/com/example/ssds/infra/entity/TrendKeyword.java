package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * 追蹤中的關鍵字（規格書 §7.2 trend_keyword）。
 *
 * <p>B 軌以關鍵字為起點：還沒有 product 之前就能開始累積熱度，
 * 成案轉軌時這些歷史資料直接沿用（AC-16-5）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trend_keyword")
public class TrendKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String keyword;

    /** 地區碼，預設 TW。 */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String geo = "TW";

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    /** 停用後排程不再採集，但既有熱度資料保留。 */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
