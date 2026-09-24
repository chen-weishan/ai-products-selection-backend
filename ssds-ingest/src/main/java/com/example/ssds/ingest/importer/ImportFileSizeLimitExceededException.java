package com.example.ssds.ingest.importer;

/** 暫存串流超過 FR-09 的單檔硬上限。 */
public class ImportFileSizeLimitExceededException extends RuntimeException {
    public ImportFileSizeLimitExceededException(long maxBytes) {
        super("匯入檔案不可超過 " + maxBytes + " bytes，請分批上傳");
    }
}
