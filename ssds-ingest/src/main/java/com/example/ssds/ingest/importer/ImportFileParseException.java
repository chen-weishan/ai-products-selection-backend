package com.example.ssds.ingest.importer;

/** 無法辨識匯入檔案或檔案結構不合法。 */
public class ImportFileParseException extends RuntimeException {
    public ImportFileParseException(String message) {
        super(message);
    }

    public ImportFileParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
