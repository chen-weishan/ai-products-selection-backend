package com.example.ssds.ingest.importer;

/** 解析到第 hardMaxRows + 1 列時立即中止，不再繼續掃描大檔。 */
public class ImportRowLimitExceededException extends RuntimeException {
    private final int hardMaxRows;

    public ImportRowLimitExceededException(int hardMaxRows) {
        super("匯入檔案不可超過 " + hardMaxRows + " 列，請分批上傳");
        this.hardMaxRows = hardMaxRows;
    }

    public int getHardMaxRows() {
        return hardMaxRows;
    }
}
