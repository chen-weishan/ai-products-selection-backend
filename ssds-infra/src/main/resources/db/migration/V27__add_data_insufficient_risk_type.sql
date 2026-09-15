-- risk_alert.risk_type 補上 DATA_INSUFFICIENT（資料不足，無法評分）。
--
-- 這是本 repo 第一次「實作先行於規格」，理由記在這裡：
--
-- §5.7 規定加分因子缺 4 項以上時「不產生分數，狀態標示『資料不足，無法評分』」，
-- 但 v3.0 全文沒有指定這個狀態要如何進入 FR-10 的示警清單，
-- §7.2.8 L2954 的 risk_type 完整列舉裡也沒有對應值。
--
-- V23 的 product.last_scoring_status 只承接得了「現在這一刻算不算得出分數」：
-- 沒有時序、沒有 OPEN／ACKNOWLEDGED 的處理狀態、也不會進入 FR-10 的待辦清單，
-- 操作人員無從得知哪些品項因缺資料而卡住、該補什麼。要讓「明確標示」真的被看見，
-- 必須有一筆可追蹤、可處理的示警。
--
-- 版號說明：本分支（chen-weishan）落後 dev，本機只有到 V23，但共用資料庫已套用到
-- V26（V24 import mapping template／V25 daily ai budget usage／V26 link calibration
-- reports to ai tasks）。取 V27 以避開已被佔用的版號。
--
-- 決議來源：PR #10 review。規格書本身尚未更新，下次校訂規格時應把
-- DATA_INSUFFICIENT 補進 §FR-10-1 的示警類型表與 §7.2.8 的列舉。
--
-- 預設嚴重度 MEDIUM 是本次的設計決定，非規格指定：比 LOW_CONFIDENCE（仍算得出
-- 分數、只是不確定）嚴重，但不到 HIGH（HIGH 在 §FR-10-1 保留給會直接影響
-- 採購決策的風險，資料不足只是還不能判斷）。

ALTER TABLE risk_alert
    DROP CONSTRAINT IF EXISTS ck_risk_alert_type;

ALTER TABLE risk_alert
    ADD CONSTRAINT ck_risk_alert_type
        CHECK (risk_type IN (
            'REVIEW_RISK', 'LOGISTICS_RISK', 'INVENTORY_RISK', 'PENALTY_CAP',
            'HEAT_CRASH', 'HEAT_SURGE', 'SEASON_MISMATCH',
            'FESTIVAL_WINDOW_CLOSING', 'LOW_CONFIDENCE', 'DATA_INSUFFICIENT'));

COMMENT ON COLUMN risk_alert.risk_type IS
    '示警類型。§FR-10-1 九項，外加 DATA_INSUFFICIENT（§5.7 資料不足，無法評分）';
