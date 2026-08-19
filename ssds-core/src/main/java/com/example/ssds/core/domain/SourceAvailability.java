package com.example.ssds.core.domain;

/**
 * 熱度來源可用性（規格書 FR-14-2、§7.2 heat_source.availability）。
 *
 * <p>降級行為（§5.3.2）：來源不可用時其 composite_weight 歸零，
 * 其餘來源按比例重新正規化，分數照常產生並於 UI 標示缺漏，
 * 不阻斷評分流程。
 */
public enum SourceAvailability {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE
}
