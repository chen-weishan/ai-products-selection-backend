package com.example.ssds.core.domain;

/**
 * 選品分級（規格書 §5.6）。
 *
 * <p>預設門檻 A 為 85 以上、B 為 70–84、C 為 70 以下，但 v2.0 起各排行榜可獨立
 * 設定門檻（話題爆款榜與常態補貨榜的分數分佈不同），因此門檻值存於
 * weight_version.grade_a_threshold / grade_b_threshold，不寫死在此。
 */
public enum Grade {
    /** 主推 */
    A,
    /** 備選 */
    B,
    /** 觀察 */
    C
}
