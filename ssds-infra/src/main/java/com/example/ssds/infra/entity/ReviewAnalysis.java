package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Sentiment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * 評論分析結果（規格書 §7.2 review_analysis）。
 *
 * <p>v2.0 角色變更：v1.0 把評論情感當加分因子，v2.0 改為<b>只在負面且集中於
 * 特定風險主題時扣分</b>（§5.2.2）。評價普通不影響分數，但食安類負評會被明確標示。
 *
 * <p>與 {@link ProductReview} 共用主鍵：{@code @MapsId} 讓 review_id 同時是
 * 主鍵與外鍵，省掉一個沒有意義的流水號，也讓 1:1 的關係在資料庫層就成立。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "review_analysis")
public class ReviewAnalysis {

    @Id
    @Column(name = "review_id")
    private Long reviewId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id")
    private ProductReview review;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sentiment sentiment;

    /** v3.0：僅負評有值，且限定為 ReviewRiskAgent 的五種正式主題。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_topic", length = 24)
    private ReviewRiskTopic riskTopic;

    @Column(name = "key_phrase", length = 50)
    private String keyPhrase;

    /** 產生此分析的模型 ID，換模型後可回溯比較品質。 */
    @Column(length = 80)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "analyzed_at", nullable = false)
    @Builder.Default
    private Instant analyzedAt = Instant.now();
}
