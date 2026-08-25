package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 報表產出任務（規格書 §7.2.11 report_job、FR-12）。v3.0 新增。
 *
 * <p>v2.0 有三個報表端點卻沒有任何資料表承載任務狀態與檔案位置，「下載」無從實作。
 *
 * <p>status 沿用 {@link TaskStatus}：本表的值域（PENDING／RUNNING／SUCCEEDED／FAILED）
 * 是它的子集，沒有值域差異可支撐拆出獨立 enum。子集關係表示 Java 端擋不掉
 * PARTIAL／CANCELLED，由 DB 的 ck_report_job_status 負責攔截。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "report_job")
public class ReportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 24)
    private ReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ReportFormat format;

    /** 篩選條件。DB 端 ck_report_job_params 要求必須是 JSON object，不可為陣列或純量。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String paramsJson = "{}";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    /** 產出檔案位置。SUCCEEDED 時必填，由 ck_report_job_file 強制。 */
    @Column(name = "file_path", length = 255)
    private String filePath;

    @Column(name = "row_count")
    private Integer rowCount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private AppUser requestedBy;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
