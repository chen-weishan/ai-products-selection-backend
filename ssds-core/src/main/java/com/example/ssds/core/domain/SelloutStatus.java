package com.example.ssds.core.domain;

/** 售罄狀況（規格書 FR-11-2、§7.2 campaign_result.sellout_status）。 */
public enum SelloutStatus {
    /** 提前售罄 */
    EARLY_SELLOUT,
    /** 如期完售 */
    ON_TIME,
    /** 未達標 */
    BELOW_TARGET,
    /** 滯銷 */
    SLOW
}
