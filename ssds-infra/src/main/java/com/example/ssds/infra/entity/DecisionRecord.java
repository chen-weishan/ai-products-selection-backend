package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.DecisionType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

/**
 * 採購決策（規格書 §7.2 decision_record、FR-11-1）。
 *
 * <p>AC-11-1：決策必須綁定一筆評分快照，無快照時不可建立決策，故
 * {@link #score} 為 NOT NULL。AC-11-6：綁定的是不可變的歷史評分列，
 * 權重改版不會回頭改動它。
 *
 * <p>AC-11-2：未採納 AI 建議時 {@link #reason} 必填 —— 這是整套
 * 「越用越準」的關鍵訊號，人為什麼不同意 AI 比 AI 說了什麼更有價值。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "decision_record")
public class DecisionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "score_id", nullable = false)
    private ProductScore score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DecisionType decision;

    @Column(name = "followed_ai", nullable = false)
    @Builder.Default
    private boolean followedAi = true;

    /** 決策當下參考的 AI 建議；AI 未產生建議時為 null。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_insight_id")
    private AiInsight aiInsight;

    @Column(name = "first_order_qty")
    private Integer firstOrderQty;

    @Column(name = "expected_list_date")
    private LocalDate expectedListDate;

    @Column(length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decided_by", nullable = false)
    private AppUser decidedBy;

    @Column(name = "decided_at", nullable = false)
    @Builder.Default
    private Instant decidedAt = Instant.now();

    @OneToOne(mappedBy = "decision", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private DecisionFeedback feedback;

    @OneToOne(mappedBy = "decision", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private CampaignSnapshot snapshot;

    @OneToOne(mappedBy = "decision", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private CampaignResult result;

    /** AC-11-3：結案滿 7 天未回填者，於儀表板待辦區提示。 */
    public boolean isFeedbackOverdue(Instant now) {
        return result == null
                && expectedListDate != null
                && decidedAt.plusSeconds(7L * 24 * 3600).isBefore(now);
    }
}
