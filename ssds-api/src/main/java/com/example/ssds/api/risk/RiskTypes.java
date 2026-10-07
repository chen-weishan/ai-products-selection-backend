package com.example.ssds.api.risk;

import java.util.Set;

/**
 * 示警類型代碼（{@code risk_alert.risk_type}）的單一出處。
 *
 * <p>字串值必須與資料庫的 {@code ck_risk_alert_type} 檢查約束一致（V17 起九項，V27 加入
 * {@code DATA_INSUFFICIENT}）；{@link RiskAlertWriter} 會拒絕不在 {@link #ALL} 內的類型，
 * 避免拼錯的代碼到了資料庫才被約束擋下。
 */
public final class RiskTypes {

    public static final String REVIEW_RISK = "REVIEW_RISK";
    public static final String LOGISTICS_RISK = "LOGISTICS_RISK";
    public static final String INVENTORY_RISK = "INVENTORY_RISK";
    public static final String PENALTY_CAP = "PENALTY_CAP";
    public static final String HEAT_CRASH = "HEAT_CRASH";
    public static final String HEAT_SURGE = "HEAT_SURGE";
    public static final String SEASON_MISMATCH = "SEASON_MISMATCH";
    public static final String FESTIVAL_WINDOW_CLOSING = "FESTIVAL_WINDOW_CLOSING";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    /** §5.7 資料不足、無法評分（V27 新增）。 */
    public static final String DATA_INSUFFICIENT = "DATA_INSUFFICIENT";

    /** 全部合法的示警類型。 */
    public static final Set<String> ALL = Set.of(
            REVIEW_RISK, LOGISTICS_RISK, INVENTORY_RISK, PENALTY_CAP,
            HEAT_CRASH, HEAT_SURGE, SEASON_MISMATCH,
            FESTIVAL_WINDOW_CLOSING, LOW_CONFIDENCE, DATA_INSUFFICIENT);

    private RiskTypes() {
    }
}
