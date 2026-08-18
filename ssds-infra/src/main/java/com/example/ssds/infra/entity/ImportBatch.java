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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
