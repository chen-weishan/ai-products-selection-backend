# db/migration 使用須知

## 鐵則

已套用到任何資料庫的 migration **不得修改、刪除、重新命名**，連註解、空白、換行都不行。
Flyway 以 CRC32 檢查 checksum，改一個字元就會讓所有人的後端啟動失敗。
需要修正時一律建立下一個尚未使用的版本號。

不要自行執行 `flyway repair`。checksum 不符時先確認是誰改了已套用的檔案，
從正確的 `dev` 版本還原，而不是把記錄改成配合本機檔案。

## V8～V11 檔頭的 DRAFT 字樣已失效

`V8`、`V9`、`V10`、`V11` 四支檔案的開頭寫著：

> DRAFT — This file intentionally lives outside db/migration.
> Flyway must not run it until the matching entity/repository/service is implemented and reviewed.

**這段話是錯的。** 這四支檔案連同 `V7` 已於 2026-08-19 套用至共用資料庫
（見 `flyway_schema_history` 的 installed_rank 13–17）。
原因是草稿檔搬進 `db/migration` 時沒有一併改掉檔頭，而檔案套用後就不能再改。

以 `flyway_schema_history` 為準，不要以檔頭註解為準。

## 版本號分配

| 範圍               | 用途                            | 載入時機            |
| ------------------ | ------------------------------- | ------------------- |
| `V1`–`V899`   | 正式 schema，放`db/migration` | 所有 profile        |
| `V900`–`V999` | 開發用假資料，放`db/dev`      | 只有`dev` profile |

假資料固定編在 V900 之後，代價是開發庫的版本序會亂序，
因此 `application-dev.properties` 開了 `spring.flyway.out-of-order=true`。
`prod` profile 不載入 `db/dev`，用不到這個開關。

## 新增 migration 的流程

1. 取新版本號前先 `git fetch origin` 並看過 `origin/dev` 上已用掉哪些號碼。
   本機未推送的檔案若與 `dev` 撞號，一律**把自己的改成更大的號碼**，
   不要試圖保留原編號——`dev` 上的那支可能已經套用到共用庫，改不動了。
2. 在本機以 Testcontainers 驗證（若本機沒有docker，可以把檔案給維杉請他幫忙測試你新增的所有 migration 檔案，測試完確認沒問題後這步可跳過）：
   `.\gradlew.bat :ssds-infra:test`
   每個案例各起一個乾淨的 PostgreSQL 容器，從 `V1` 一路建到最新。
   需要 Docker Desktop 執行中，約 30 秒。四個案例分別驗：
   prod 版面（只有 `db/migration`）、dev 版面（加上 `db/dev` 假資料）、
   所有表都啟用 RLS、以及模擬 Supabase 角色時權限確實被收乾淨。
3. 新 migration 若建了資料表，同一支檔案內要加上
   `ALTER TABLE <表名> ENABLE ROW LEVEL SECURITY;`，
   否則第三個案例會失敗（V12 只涵蓋它套用當下已存在的表）。
4. 開 PR，**不要先套用共用資料庫**。
5. PR 通過並合併到 `dev` 之後，才由負責人對共用資料庫套用。
