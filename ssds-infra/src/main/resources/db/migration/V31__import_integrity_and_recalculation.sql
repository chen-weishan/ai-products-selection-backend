-- FR09 匯入完整性與重算復原：新增所需結構，不為舊銷售資料補造來源識別。
-- 將重複略過筆數與成功、失敗筆數分開，供進度計算及未處理資料下載使用。
ALTER TABLE import_batch ADD COLUMN skipped_rows INTEGER NOT NULL DEFAULT 0 CHECK (skipped_rows >= 0);

-- 修正舊版失敗批次將未處理資料計入失敗筆數的情況。
-- 依實際錯誤資料列重新計數；同列多個錯誤只算一次，排除第 0 列的批次說明。
UPDATE import_batch b SET fail_rows=(
 SELECT count(DISTINCT e.row_number) FROM import_error e WHERE e.batch_id=b.id AND e.row_number>0
) WHERE b.status='FAILED';

-- 保存銷售唯一識別與內容摘要，防止跨批次重複寫入，並辨識同識別的內容衝突。
CREATE TABLE import_sales_identity (
 identity_key CHAR(64) PRIMARY KEY,
 payload_hash CHAR(64) NOT NULL,
 batch_id BIGINT NOT NULL REFERENCES import_batch(id)
);

-- 保存檔案內容與欄位對應的指紋，防止同一份銷售檔案再次確認匯入。
CREATE TABLE import_file_claim (
 fingerprint CHAR(64) PRIMARY KEY,
 batch_id BIGINT NOT NULL REFERENCES import_batch(id)
);

-- 將重算工作持久化；每個批次與品項只有一筆任務，支援中斷恢復及失敗重試。
-- 任務須與匯入資料一起提交，評分結果則與任務完成狀態一起提交。
CREATE TABLE import_recalculation_task (
 batch_id BIGINT NOT NULL REFERENCES import_batch(id),
 product_id BIGINT NOT NULL REFERENCES product(id),
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
   CHECK (status IN ('PENDING','SCORED','INSUFFICIENT_DATA','SKIPPED','FAILED')),
 attempts INTEGER NOT NULL DEFAULT 0,
 last_error VARCHAR(500),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ,
 PRIMARY KEY(batch_id, product_id)
);

-- 加速背景工作依可執行時間查找待重算任務。
CREATE INDEX idx_import_recalculation_pending ON import_recalculation_task(next_attempt_at)
 WHERE status = 'PENDING';

-- 啟用資料列層級安全性；若應用程式角色已存在，授予所需權限並建立存取政策。
DO $$
DECLARE t TEXT;
BEGIN
 FOREACH t IN ARRAY ARRAY['import_sales_identity','import_file_claim','import_recalculation_task'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ssds_app') THEN
   EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO ssds_app',t);
   EXECUTE format('CREATE POLICY p_ssds_app_rw ON %I FOR ALL TO ssds_app USING(true) WITH CHECK(true)',t);
  END IF;
 END LOOP;
END $$;
