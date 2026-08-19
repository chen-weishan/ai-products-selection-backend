package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.PostNoteCode;
import com.example.ssds.core.domain.SelloutStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/**
 * 開團結果（規格書 §7.2 campaign_result、FR-11-2）。
 *
 * <p>FR-15 權重校準的標籤資料。沒有這張表，統計迴歸完全不成立 ——
 * 迴歸需要「分數」與「實際結果」成對出現，本表提供的就是後者。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "campaign_result")
public class CampaignResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false, unique = true)
    private DecisionRecord decision;

    @Column(name = "actual_qty", nullable = false)
    private int actualQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "sellout_status", nullable = false, length = 24)
    private SelloutStatus selloutStatus;

    /** 退貨／客訴率，百分比（0–100）。 */
    @Column(name = "return_rate", precision = 5, scale = 2)
    private BigDecimal returnRate;

    /** 實現毛利率（百分比），對照當初預估毛利。 */
    @Column(name = "realized_margin", precision = 5, scale = 2)
    private BigDecimal realizedMargin;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_note_code", length = 32)
    private PostNoteCode postNoteCode;

    @Column(name = "post_note_text", length = 255)
    private String postNoteText;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "filled_by", nullable = false)
    private AppUser filledBy;

    @Column(name = "filled_at", nullable = false)
    @Builder.Default
    private Instant filledAt = Instant.now();
}
