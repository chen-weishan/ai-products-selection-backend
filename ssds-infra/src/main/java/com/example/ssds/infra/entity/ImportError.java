package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 匯入錯誤明細（規格書 §7.2 import_error）。
 *
 * <p>AC-09-2：失敗列可下載、修正後重新上傳，所以必須連 {@link #rawRow}
 * 原文一起留著 —— 只存錯誤訊息的話使用者拿不回原始資料。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "import_error")
public class ImportError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ImportBatch batch;

    /** 檔案內的列號（自 1 起算，含標題列的偏移由匯入器決定）。 */
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "column_name", length = 64)
    private String columnName;

    @Column(name = "error_message", nullable = false, length = 500)
    private String errorMessage;

    @Column(name = "raw_row", columnDefinition = "text")
    private String rawRow;
}
