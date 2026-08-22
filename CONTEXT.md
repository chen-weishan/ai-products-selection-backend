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

## 2. 規格書與實作不一致，以實作為準

共用資料庫（Supabase project `aozddonvsfdrtwpuxnqi`）已落地的內容不隨規格書改。

| 項目 | 規格書寫 | 實作 | 裁決 |
|---|---|---|---|
| 加分因子代碼 | §7.2.5：`TREND`、`CVR` | `HEAT_SLOPE`、`CONVERSION` | **以實作為準**，共用 DB 已有資料 |
| 版本生效狀態 | `status` DRAFT／APPROVED／RETIRED + `is_current` 旗標 | `status` DRAFT／**ACTIVE**／RETIRED + partial unique index | **以實作為準**。語意等價，且用索引保證「同時只有一個生效版本」比旗標更嚴格 |
| 分級門檻 | `weight_version` 上的純量欄位 | `grade_threshold` 表（V13 建立），四榜各一列；`weight_version.grade_a_threshold`／`grade_b_threshold` 仍存在且 NOT NULL | 讀取一律走 `grade_threshold` 表。建立草稿時，純量欄位填 VIRAL 榜的值以滿足 NOT NULL。**此為已知技術債，暫不清理** |
| `HEAT_VOLUME` | v3.0 §5.2.1-a 已降為門檻條件，不進權重 | `FactorCode` enum 仍保留此值 | enum 保留無妨，但**不得寫入 `weight_profile`** |
| 資料庫 | §3.2 寫 MySQL | Supabase PostgreSQL | **以實作為準** |

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
