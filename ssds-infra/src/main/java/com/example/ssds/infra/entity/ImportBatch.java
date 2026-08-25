package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/** 匯入批次（規格書 §7.2 import_batch、FR-09）。部分成功時狀態為 PARTIAL。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 32)
    private ImportDataType dataType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 位元組。FR-09 依 2MB／50MB 兩道門檻決定同步、非同步或拒收。 */
    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "total_rows", nullable = false)
    @Builder.Default
    private int totalRows = 0;

    @Column(name = "success_rows", nullable = false)
    @Builder.Default
    private int successRows = 0;

    @Column(name = "fail_rows", nullable = false)
    @Builder.Default
    private int failRows = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /** 超過同步門檻（&gt; 2MB 或 &gt; 5,000 列）轉背景任務，前端改用輪詢查進度（AC-09-5）。 */
    @Column(name = "is_async", nullable = false)
    @Builder.Default
    private boolean async = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 失敗一樣是結束，FAILED 時同樣有值。 */
    @Column(name = "finished_at")
    private Instant finishedAt;
}
