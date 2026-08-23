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

    /**
     * 主鍵即 decision_id（1:1，§7.2.8）。v2.0 另有一個代理鍵 id，
     * 但本表與 decision_record 永遠一對一，多一個代理鍵只是多一條可以寫錯的路徑。
     */
    @Id
    @Column(name = "decision_id")
    private Long decisionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id")
    private DecisionRecord decision;

    @Column(name = "actual_qty", nullable = false)
    private int actualQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "sellout_status", nullable = false, length = 24)
    private SelloutStatus selloutStatus;

    /**
     * 退貨／客訴率，比率 0–1。
     *
     * <p>v3.0 §7.2.8 把所有比率欄位統一為 DECIMAL(5,4)：v2.0 同一個概念
     * 在兩張表上一個是 (5,4)、一個是 (5,2)，值到底是 1.2 還是 0.012 講不清楚。
     */
    @Column(name = "return_rate", precision = 5, scale = 4)
    private BigDecimal returnRate;

    /**
     * 實現毛利率，比率 0–1（必填）。
     *
     * <p>v2.0 叫 realized_margin 且未說明是金額還是率；若為金額則上限 999.99
     * 明顯不足。v3.0 更名為 realized_margin_rate 以消除歧義。
     */
    @Column(name = "realized_margin_rate", precision = 5, scale = 4)
    private BigDecimal realizedMarginRate;

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
