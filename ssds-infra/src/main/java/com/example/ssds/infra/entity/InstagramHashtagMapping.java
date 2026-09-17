package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Instagram hashtag → 品類對照（V29）。
 *
 * <p>取代原本寫死在 {@code ssds-api/config/InstagramHashtagMapping} 的靜態常數清單
 * ——那個版本改對照關係要改 Java 重新編譯部署，且用「品類名稱字串」比對，
 * 查無品類只在排程執行時記警告跳過，問題要等排程跑過才會被發現。
 *
 * <p>改用 FK 直接關聯 {@link Category} 後，查無品類這件事在「新增/停用
 * 一筆對照」的當下（寫入這張表時）就會被資料庫擋下，不必等排程執行。
 * {@link com.example.ssds.api.schedule.InstagramHeatIngestJob} 只需依
 * {@code enabled = true} 撈出要採集的 hashtag，不再需要自行解析名稱。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "instagram_hashtag_mapping")
public class InstagramHashtagMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String hashtag;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
