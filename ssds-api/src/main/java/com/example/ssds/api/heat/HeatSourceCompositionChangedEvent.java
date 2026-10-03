package com.example.ssds.api.heat;

import com.example.ssds.core.domain.HeatSourceCode;

/**
 * 熱度來源的合成權重或啟用狀態已變動（AC-14-5）。
 *
 * <p>只在 {@code HeatSourceCommandService.update} 偵測到「值真的不同」時發布；
 * 監聽端須用 AFTER_COMMIT，才讀得到已提交的新權重。
 */
public record HeatSourceCompositionChangedEvent(Long sourceId, HeatSourceCode sourceCode) {}
