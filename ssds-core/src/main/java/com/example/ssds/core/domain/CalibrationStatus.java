package com.example.ssds.core.domain;

/**
 * 校準報告審核狀態（規格書 FR-15、§7.2 calibration_report.status）。
 *
 * <p>PARTIAL 對應 AC-15-5 的「部分採納」：可逐項勾選要接受的調整。
 */
public enum CalibrationStatus {
    PENDING,
    APPROVED,
    PARTIAL,
    REJECTED
}
