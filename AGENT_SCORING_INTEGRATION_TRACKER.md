# Agent 與評分／因子模組整合追蹤文件

> 文件狀態：進行中  
> 建立日期：2026-09-17  
> 最後更新：2026-09-17  
> 工作分支：`integration/agent-scoring`  
> 建立基準：`8542177`（整合最新 `dev` 與 `Demisak` Agent 流程）

## 1. 文件目的

本文件是 Agent、正式評分與因子分數模組的整合計畫及持續紀錄。後續每次相關程式碼變更都必須同步更新：

- 本次完成的工作與影響範圍。
- 尚未解決的問題與新增風險。
- 執行過的測試及結果。
- 契約、架構或規格裁決。
- 對應 commit；尚未 commit 時標示 `working tree`。

本文件不取代開發規格書、API 文件或 migration 說明；它負責記錄「如何把既有模組整合成可驗收流程」。

## 2. 依據與優先序

### 2.1 參考來源

1. [開發規格書 v3.0／v3.0.1](../../開發規格書_v3.0.md)
2. [畫面功能示意圖 v3.0](../../畫面功能示意圖_v3.0.html)
3. [v3.0.1 AI 細節持續稽核報告](../../SPEC_V3_AI_CONTINUOUS_AUDIT_REPORT.md)
4. 目前 `integration/agent-scoring` 實際程式碼與測試
5. 後續人工裁決與本文件的決策紀錄

### 2.2 衝突處理原則

1. 使用者最新明確裁決優先。
2. 資料權威、AI 權責及驗收行為以開發規格書 v3.0.1 為準。
3. 畫面示意圖用於確認呈現及操作流程，不視為後端已完成的證據。
4. 稽核報告反映特定 commit／工作目錄的檢查結果；使用前須以目前程式重新驗證。
5. 文件與程式不一致時，不默認任一方正確；先記錄差異，再以測試及人工裁決收斂。

## 3. 已確定的架構裁決

| 項目 | 裁決 | 狀態 |
|---|---|---|
| 整合基底 | 從最新 `origin/dev` 建立 `integration/agent-scoring`，再合入本地 `Demisak` | 已完成 |
| FULL_ANALYSIS 執行流程 | 以 `Demisak` 的 `AiTaskWorker`、`AiTaskService`、`FullAnalysisOrchestrator` 為主 | 已確認 |
| dev poller/executor | 不作為目前 FULL_ANALYSIS 執行入口 | 已解決，不列阻塞 |
| 評分查詢 | 保留 dev 的排行、快照、歷史、扣分明細與模擬契約 | 已合入 |
| Agent 權責 | Agent 不直接建立權重、門檻、正式分數或人工決策 | 持續約束 |
| 評分可用性 | LLM 全部停用或失敗時，純計算評分仍須產生 | 待正式驗收 |
| 歷史保存 | 重評建立新快照；舊 `product_score` 不覆寫，只轉為 inactive | 待正式驗收 |

## 4. 目標端到端流程

### 4.1 每品項完整分析

```text
建立 FULL_ANALYSIS task/item
  → Agent 2 ReviewRisk：分析評論並保存 review_analysis
  → Agent 1 SceneClassifier：產出離散情境與備選情境
  → 正式 ScoreEvaluationService
      → 讀取六個加分因子的權威資料
      → 依固定規則計算三個扣分因子
      → 套用人工管理的情境權重與分級門檻
      → 以同一交易保存 product_score + 9 筆 score_factor
      → 回傳本次主情境 scoreId 及其他情境 scoreId
  → Agent 3 ProductInsight：讀取本次主情境快照
  → Agent 4 Recommendation：讀取同一筆主情境快照
  → 更新 task/item 統計、警告與完成狀態
```

必要順序：`Agent 2 → Agent 1 → 正式評分 → Agent 3 → Agent 4`。

Agent 3、4 不可自行重新查詢「當下最新一筆」作為唯一契約；正式整合應傳遞或鎖定本次 `scoreId`，避免同品項併發重評時讀到不同快照。

### 4.2 純計算評分

```text
排程、資料匯入、權重生效或人工要求重算
  → 取得最近一次有效情境／人工覆寫情境
  → 無有效情境時使用 REPLENISHMENT 並標示降級
  → 計算六因子與三扣分
  → 建立新快照並更新評分結果狀態
  → 不呼叫任何 LLM、不消耗 AI 配額
```

### 4.3 批次分層

全品項純計算與 LLM 分析必須分層：

- 所有符合資格的 A 軌品項都執行純計算評分。
- 每輪最多 150 品項進行 LLM 增益分析，實際上限由設定控制。
- 超出上限、配額不足或外部 LLM 停用，不得造成品項缺少分數。
- AI 失敗項依既有 task/item 規則續跑或人工重跑，不回滾已完成的正式分數。

## 5. 模組責任邊界

### 5.1 Agent 層

| Agent | 正式評分前後 | 可寫入 | 不可寫入 |
|---|---|---|---|
| Agent 1 SceneClassifier | 評分前 | 情境判定紀錄、信心度、模型資訊 | 權重值、分級門檻、正式分數 |
| Agent 2 ReviewRisk | 評分前 | `review_analysis` | `score_factor`、`product_score`、人工決策 |
| Agent 3 ProductInsight | 評分後 | 賣點與風險文字 insight | 扣分值、正式分數 |
| Agent 4 Recommendation | 評分後 | 建議 action／數量區間／理由 | `decision_record`、首批實際數量 |
| Agent 5 TrendInterpreter | 關鍵字資料增益 | 趨勢解讀與規格允許的覆寫欄位 | 每品項正式分數 |
| Agent 6 SourcingScout | B 軌探索 | 探索報告、機會／風險訊號 | 熱度階段權威欄位、A 軌分數 |
| Agent 7 WeightCalibration | 校準解讀 | AI 解讀、建議、注意事項 | 權重版本、權重值、門檻、核准結果 |

### 5.2 正式評分服務

正式 `ScoreEvaluationService` 應負責：

- 組裝六加分因子與三扣分因子的同次計算輸入。
- 驗證資料可用性與資料不足門檻。
- 執行情境權重缺值分攤、加分小計、扣分小計、封頂、分級與信心度計算。
- 管理主情境／次要情境快照。
- 管理 active/inactive 歷史與交易一致性。
- 呼叫單一評分結果記錄機制，更新 `last_scoring_status` 與資料不足示警。
- 回傳本次建立的確切快照識別，不把「查最新」留給下游 Agent。

正式評分服務不得呼叫 LLM，也不得依 AI 自行產生的連續數值修改權重或扣分。

### 5.3 因子提供者

六個加分因子：

| 因子 | 權威來源／計算方向 | 現況 |
|---|---|---|
| `TREND` | 熱度合成、斜率及同品類百分位；不滿最小時間窗時無資料 | 待建立正式 provider |
| `MARGIN` | 品項毛利率與同品類百分位 | fallback 已有部分能力 |
| `CVR` | 歷史銷售／轉換資料與同品類百分位 | 待建立正式 provider |
| `PRICE_FIT` | 客群價格帶與品項價格的適配度；資料不可得時無資料 | 待資料契約與 provider |
| `FESTIVAL` | 節慶時間窗、品項關聯度與規格公式 | 待建立正式 provider |
| `CLIMATE` | 歷史同期氣候與品項／品類適溫資料 | 待建立正式 provider |

三個扣分因子：

| 因子 | 計算方向 | 現況 |
|---|---|---|
| `REVIEW_RISK` | 由 Agent 2 保存的評論分析，依固定規則計算；Agent 不直接給扣分值 | 尚未接入正式重算 |
| `LOGISTICS_RISK` | 由物流條件固定規則計算 | fallback 已有部分能力 |
| `INVENTORY_RISK` | 由效期、MOQ、季節性等固定規則計算 | fallback 已有部分能力 |

每個 provider 至少回傳：`rawValue`、`normalizedValue`、`dataAvailable`、`imputed`、`note`，以及規格要求的來源識別欄位。

## 6. 開工順序與里程碑

### Phase 0：整合基準

- [x] 從最新 `origin/dev` 建立 `integration/agent-scoring`。
- [x] 合入本地 `Demisak`，包含 `575cea9`。
- [x] 衝突保留雙方必要的 `ProductScoreRepository` 契約。
- [x] 以 `Demisak` FULL_ANALYSIS 流程為主。
- [x] Production/test source 重新編譯成功。
- [x] Agent／評分關鍵窄測試通過。
- [x] 推送 `origin/integration/agent-scoring`。

### Phase 1：正式評分契約與責任收斂

- [ ] 定義最小 `ScoreEvaluationService` 輸入／輸出契約。
- [ ] 輸出包含本次主情境 `scoreId`，並支援次要情境快照。
- [ ] 將 `ScoreRecalculationService` 標定為待替換橋接器，不再擴大其責任。
- [ ] 收斂 `ScoreRecalculationService` 與 `ScoringResultRecorder` 重複的狀態／示警邏輯。
- [ ] 補服務契約測試，先鎖定交易、歷史與資料不足行為。

完成標準：服務可以用 fixture 接收九因子值，原子性建立可供查詢 API 使用的新快照。

### Phase 2：因子 provider

- [ ] MARGIN provider。
- [ ] LOGISTICS_RISK provider。
- [ ] INVENTORY_RISK provider。
- [ ] REVIEW_RISK provider，從 `review_analysis` 固定計算。
- [ ] TREND provider。
- [ ] CVR provider。
- [ ] PRICE_FIT provider 或明確停用／無資料策略。
- [ ] FESTIVAL provider。
- [ ] CLIMATE provider 或明確停用／無資料策略。
- [ ] 每個 provider 補正常、邊界、無資料及 imputed 測試。

完成標準：相同資料輸入可重現相同九因子輸出，不依賴 LLM。

### Phase 3：正式評分與快照交易

- [ ] 接入 `ScoringEngine`。
- [ ] 套用有效 `weight_version`、情境權重組與分級門檻。
- [ ] 實作缺資料權重分攤與最少三個可用加分因子門檻。
- [ ] 實作多情境評分、主情境標記及唯一鍵行為。
- [ ] 同一交易保存 `product_score` 與九筆 `score_factor`。
- [ ] 新快照成功後才停用舊 active 快照；失敗不得留下半套資料。
- [ ] 統一 `SCORED`／`INSUFFICIENT_DATA` 與七日示警去重。
- [ ] 通過規格黃金案例。

完成標準：正式服務取代橋接器，歷史、active 狀態、因子列與查詢 API 一致。

### Phase 4：Agent 串接

- [ ] `FullAnalysisOrchestrator` 改呼叫正式評分服務。
- [ ] Agent 2 的本次結果在評分前可被 REVIEW_RISK provider 讀取。
- [ ] Agent 1 的 `finalSceneType` 決定本次主情境權重組。
- [ ] Agent 3、4 明確使用本次主情境 `scoreId`。
- [ ] Agent 3、4 失敗不回滾正式分數。
- [ ] 評分資料不足時，不執行依賴正式分數的 Agent 3、4，task/item 正確標記未完成。
- [ ] 補單品項整合測試及 DB-backed 權責邊界回歸測試。

完成標準：一次 FULL_ANALYSIS 內的 Agent 3、4 可證明讀取同一次產生的快照。

### Phase 5：批次分層與降級

- [ ] 全品項純計算評分與 LLM 150 品項上限分離。
- [ ] 外部 LLM 停用時仍完成全品項評分。
- [ ] 配額耗盡只影響 AI 增益，不影響正式分數。
- [ ] 匯入後受影響品項重算不建立 AI task。
- [ ] 權重版本生效後觸發純計算重算。
- [ ] 驗證失敗項續跑與人工重跑不重複建立錯誤快照。

完成標準：500 品項純計算不含 LLM，且符合規格效能門檻。

### Phase 6：API、前端與稽核

- [ ] 排行、快照、歷史、扣分及模擬 API 使用正式快照驗收。
- [ ] OpenAPI 與前端模型同步。
- [ ] 前端合入最新 dev 後串接排行與因子明細。
- [ ] AI 文字、系統分數、固定規則扣分與人工決策有清楚視覺區隔。
- [ ] 情境人工覆寫完成身分、權限、原因與重算閉環。
- [ ] 評分、覆寫、權重與決策的重要操作寫入 audit log。

完成標準：主要畫面流程及規格 E2E 流程可操作且可追溯。

## 7. 已知問題與風險

| ID | 問題 | 影響 | 優先序 | 狀態／處置 |
|---|---|---|---|---|
| IS-01 | 尚無正式 `ScoreEvaluationService` | 無法宣告六因子完整閉環 | P0 | Phase 1 建立 |
| IS-02 | `ScoreRecalculationService` 複製既有因子快照 | 新資料不會形成真正的新因子值 | P0 | 以正式服務取代 |
| IS-03 | fallback 主要只有 MARGIN，其他五加分因子無資料 | 分數不可視為正式六因子結果 | P0 | Phase 2 補 provider |
| IS-04 | Agent 2 結果尚未轉為固定 REVIEW_RISK 扣分 | 評論分析不影響正式扣分 | P0 | 建立固定規則 provider |
| IS-05 | Agent 3、4 目前以 latest active primary 查詢 | 併發重評可能讀到不同快照 | P0 | 傳遞本次 `scoreId` |
| IS-06 | 評分結果狀態與資料不足示警存在兩套邏輯 | 去重、時間與訊息可能不一致 | P0 | 收斂至單一 recorder |
| IS-07 | 全量評分與 LLM 分析量尚未完整拆層 | 150 上限可能造成部分品項無分數 | P0 | Phase 5 處理 |
| IS-08 | TREND／每日熱度權威資料仍依賴 ingest 完整度 | TREND 因子可能長期無資料 | P1 | 與 ingest 契約整合 |
| IS-09 | PRICE_FIT 客群價格帶可能不可得 | 唯一對應價格帶痛點的因子失效 | P1 | 明確無資料策略並揭露 |
| IS-10 | Agent 1 人工覆寫的正式認證／權限閉環未完成 | 覆寫稽核不可信 | P1 | 等認證模組後完成 |
| IS-11 | 後端完整測試基線曾有環境型失敗 | 難以判斷新回歸 | P1 | 隔離 DB、Docker 與測試金鑰 |
| IS-12 | 前端 `Demisak` 尚未合入最新前端 dev | 完整 UI 串接基準未統一 | P1 | 後端契約穩定後同步 |
| IS-13 | 多節點配額／限流非跨 JVM 原子控制 | 多節點可能超額 | P2 | 不阻塞單節點整合，另案處理 |

## 8. 驗收清單

### 8.1 正確性

- [ ] 每筆正式分數恰有六筆加分因子與三筆扣分因子。
- [ ] 六加分因子 contribution 加總等於 `bonusSubtotal`。
- [ ] 三扣分因子依規格封頂後等於 `penaltySubtotal`。
- [ ] `finalScore = max(0, bonusSubtotal - penaltySubtotal)`，並符合精度規則。
- [ ] 可用加分因子權重重新分配後合計為 `1.000`。
- [ ] 可用加分因子少於三項時不建立 `product_score`。
- [ ] 資料不足會更新產品狀態並建立或更新示警。
- [ ] 多情境快照不違反唯一鍵，且只有一筆主情境。
- [ ] 重評不覆寫歷史，新快照成功後舊快照才 inactive。
- [ ] 規格黃金案例得到指定 final score、grade、confidence 與變體結果。

### 8.2 Agent 契約

- [ ] Agent 2、3、4 執行前後，除各自允許表外的評分／決策權威資料不被直接修改。
- [ ] Agent 1 只選擇離散情境，不產生權重值。
- [ ] Agent 3、4 讀取同一個本次主情境 `scoreId`。
- [ ] Agent 4 只收到六因子百分位，不收到機敏原始值。
- [ ] AI 停用、timeout、Schema 失敗或配額不足時，正式分數仍能產生。

### 8.3 效能與批次

- [ ] 500 品項純計算評分在 60 秒內完成。
- [ ] 單品項因子計算與扣分在 200ms 內完成。
- [ ] LLM 批次上限不限制純計算品項數。
- [ ] 一筆失敗不回滾整批其他品項。

### 8.4 API 與畫面

- [ ] 排行只顯示 active 且未軟刪除品項。
- [ ] 詳情正確展開六加分、三扣分、原始值、百分位、權重與資料可用性。
- [ ] 歷史 API 可讀取 inactive 快照。
- [ ] 模擬 API 不寫入正式快照。
- [ ] AI 內容與正式分數／人工決策有清楚標示。

## 9. 測試策略

| 層級 | 必要測試 |
|---|---|
| 單元 | 每個 provider、`ScoringEngine`、扣分規則、信心度、資料不足、權重分攤 |
| Repository | active/inactive、主／次情境、排行、快照、歷史、九因子批次查詢 |
| Service | 原子保存、失敗回滾、示警去重、同次 scoreId 傳遞 |
| Agent | Prompt/Schema、降級、不得修改權威表、下游使用指定 scoreId |
| DB-backed | migration、唯一鍵、多情境、完整列快照、允許／禁止落點 |
| E2E | FULL_ANALYSIS、純計算批次、匯入後重算、權重生效重算、AI 全停用 |
| 效能 | 500 品項純計算、排行 P95、因子計算單筆延遲 |

每個 Phase 完成時至少執行該 Phase 的窄測試；準備合併前再執行可重現的完整測試。環境依賴造成的失敗必須和程式回歸分開記錄。

## 10. 決策紀錄

| 日期 | 決策 | 原因／影響 |
|---|---|---|
| 2026-09-17 | `FULL_ANALYSIS` 以 `Demisak` 程式碼為主 | 使用者確認 dev poller/executor 問題已解決，不再列為阻塞 |
| 2026-09-17 | 從最新 dev 建立 `integration/agent-scoring` 再合入 Demisak | 先取得排行、Repository、權限、migration 與測試修正，再保留 Agent 完整流程 |
| 2026-09-17 | `ProductScoreRepository` 同時保留 dev 查詢與 Demisak 寫入方法 | 評分落庫與排行／快照 API 都是整合必要契約 |
| 2026-09-17 | 正式整合不得只依賴下游查詢 latest score | 需保證 Agent 3、4 使用同一次評分快照，避免併發錯讀 |

## 11. 變更紀錄

| 日期 | Commit | Phase | 變更摘要 | 驗證 | 新增／解除問題 |
|---|---|---|---|---|---|
| 2026-09-17 | `8542177` | Phase 0 | 最新 dev 與 Demisak 合併；保留 Agent FULL_ANALYSIS；整合評分 Repository 契約 | production/test source 編譯成功；Agent／評分窄測試通過 | dev 未同步與 merge 衝突已解除 |
| 2026-09-17 | `—（文件建立）` | 文件 | 建立本追蹤文件 | Markdown 結構與連結檢查 | 無 |

## 12. 每次程式碼變更的文件更新規則

每次修改 Agent、評分、因子、任務、Repository、migration、API 或相關測試時，在同一個 commit 或緊接的文件 commit 更新本文件：

1. 更新文件頂部的「最後更新」及必要時的基準 commit。
2. 勾選或新增對應 Phase 工作項。
3. 更新「已知問題與風險」的狀態。
4. 在「變更紀錄」新增一列：日期、commit、Phase、摘要、驗證、問題變化。
5. 若改變契約或責任邊界，在「決策紀錄」新增裁決，不直接覆蓋舊決策。
6. 若測試未執行或失敗，明確記錄原因，不以「應該可用」替代結果。

完成整體串接後，本文件狀態改為「已完成」，並保留所有決策及變更歷程供驗收與維護使用。
