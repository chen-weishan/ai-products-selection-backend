# CONTEXT.md — 名詞補充表

> 主要名詞定義見《開發規格書 v3.0》§1.5。**本表只補兩類詞**：§1.5 未定義的、以及規格書自身用法分岔的。
> 兩者衝突時，以本表的裁決為準；本表未收錄的詞，一律回去查 §1.5。

---

## 1. §1.5 未定義，但規格書大量使用

| 詞 | 定義 | 依據 |
|---|---|---|
| **榜** | 四個 `SceneType` 之一，與四組情境權重一對一。規格書全文用了 91 次但 §1.5 未定義。**「榜」「情境」「情境權重組」三詞指同一個維度**，行文可換詞，程式與資料庫一律用 `SceneType` | §FR-02「不設單一總榜」 |
| **加分小計** | `product_score.bonus_subtotal` = Σ(w_i × normalized_i)，值域 0–100。**不做二次換算** | §5.5 |
| **扣分小計** | `product_score.penalty_subtotal`，資料庫存**正值** 0–40。負號只在 UI 呈現時加 | §FR-04 |
| **選品分數** | ＝加分小計 − 扣分小計。規格書另有「選品推薦分數」（3 次）與 FR-04 表格的「總分」，**三者同義**，§1.5 只定義第一個 | §5.5、§FR-04 |

---

## 2. 規格書與實作的落差：已於 2026-08-23 清空

共用資料庫（Supabase project `aozddonvsfdrtwpuxnqi`）已由 **V17** 完全對齊
《開發規格書 v3.0》§7.2，本節原本記的五條落差全部消失：

| 原落差 | 現況 |
|---|---|
| 加分因子代碼 `HEAT_SLOPE`／`CONVERSION` | 已改為規格書的 `TREND`／`CVR`；`HEAT_VOLUME` 已自 `FactorCode` 移除（§5.2.1-a 降為門檻條件） |
| 版本生效狀態 `ACTIVE` | 已改為 `APPROVED`；「生效中」改由新欄位 `is_current` 表示，partial unique index 判準一併換掉 |
| `weight_version` 的兩個純量門檻欄 | 已 `DROP COLUMN`。門檻一律讀 `grade_threshold` |
| `HEAT_VOLUME` 不得寫入 `weight_profile` | 已由 CHECK 約束保證，寫不進去 |
| 資料庫 §3.2 寫 MySQL | 仍以實作（Supabase PostgreSQL）為準。這條不會消失 |

**現在起的規則：規格書 §7.2 與資料庫不一致時，一律以規格書為準，並開一支 migration 修。**
唯二的例外，兩者都是刻意的、且不算偏離：

1. **`ENUM` 一律以 `VARCHAR(n) + CHECK` 實作**。PostgreSQL 原生 enum 新增值要 `ALTER TYPE`、
   且無法在交易中刪值，比 CHECK 難維護。屬方言轉譯，語意等價（見 V1 檔頭的完整轉譯表）。
2. **規格書欄位表未列、但實作已有的欄位一律保留**，視為實作擴充
   （如 `calibration_report` 的 AI 產出欄位、`sourcing_candidate` 的 `keyword_id`）。
   只刪規格書**明文**寫「已移除／已廢除」的。規格書未提及的表
   （`trend_interpretation`、`ai_attempt`）同理保留。

逐表稽核、逐條裁決與套用結果另有完整工作紀錄（未進版控），需要時向 schema 維護者索取。

---

## 3. 粒度陷阱

| 詞 | 直覺 | 實際 |
|---|---|---|
| **情境權重組** | §1.5 說是「一組具名的加分因子權重」，聽起來是一列 | `weight_profile` 表是 **version × scene × factor 一列，一列只存一個因子的權重**。要取得「一組」必須聚合多列 |
| **版本** | — | `weight_version` 一列＝四組權重 + 四榜門檻的完整快照 |
| **情境原型** | 與「情境權重組」是不同東西 | 共用同一個 `SceneType` enum，只是行文用詞不同 |

---

## 4. 命名裁決（規格書未規定，本專案自訂）

規格書 §8.1 只規定 JSON 欄位與錯誤碼字串，**不規定 Java 類別名**。以下為本專案裁決，勿再更換：

| 用途 | 定案 | 已放棄的別名 |
|---|---|---|
| 統一回應封套 | `ApiResponse` | `AppResponse`、`ApiErrorResponse` |
| 業務規則例外 | `BusinessException` | `ApiException` |
| 錯誤代碼 enum | `ErrorCode` | `ApiErrorCode` |

package 慣例：**每個 Gradle 模組配一段 package** —— `ssds-core` → `com.example.ssds.core`、`ssds-infra` → `com.example.ssds.infra`、`ssds-api` → `com.example.ssds.api`。避免 split package，並讓依賴方向（§3.3：api → infra → core，反向禁止）在 import 上直接可見。

---

## 5. 全組必須對齊的 API 約定

以下四條若各寫各的，合併後前端會收到不一致的形狀。**寫任何 Controller 前先讀這節。**

| 項目 | 約定 | 依據 |
|---|---|---|
| **路徑前綴** | `server.servlet.context-path=/api/v1` 已統一設定。Controller 的 `@RequestMapping` **只寫資源路徑**（`/products`），不要再寫 `/api/v1/products`，否則會變成 `/api/v1/api/v1/products` | §3.3、§8.1 |
| **日期欄位型別** | 帶時間的欄位一律用 `OffsetDateTime`；純日期用 `LocalDate`。**不要用 `Instant`**（實測輸出 `Z` 而非 `+08:00`）、**不要用 `LocalDateTime`**（沒有偏移量）。全域設定救不了型別選錯 | §8.1「回應一律以 +08:00 呈現」 |
| **分頁請求參數** | Controller 一律收 `org.springframework.data.domain.Pageable`，**不要自己宣告 `int page, int size`**。Spring 會自動把 `?page=0&size=20&sort=score,desc` 綁進去，行為與 §8.1 規定一致 | §8.1 分頁參數 |
| **分頁回應** | 一律 `ApiResponse.success(PageResponse.from(page))`，不要直接回傳 Spring Data 的 `Page` | §8.1 分頁範例 |

回應封套本身（`ApiResponse`／`ApiError`／`ErrorCode`／`GlobalExceptionHandler`）見 §4 命名裁決，全專案**只允許一個 `@RestControllerAdvice`**。

---

## 6. 開發環境

| 項目 | 約定 |
|---|---|
| 讀取測試 | 連共用 Supabase（假資料齊全） |
| 資料庫角色 | 自 V16 起一律用受限角色 `ssds_app`，不再用 `postgres`。`.env` 變數名為 `SSDS_*` |
| Spring Boot | 4.1.0 + Java 21。**starter 名稱與 Boot 3 不同**：`spring-boot-starter-webmvc`（非 `-web`）、`-aspectj`（非 `-aop`）、Flyway 自動組態為獨立 starter |
