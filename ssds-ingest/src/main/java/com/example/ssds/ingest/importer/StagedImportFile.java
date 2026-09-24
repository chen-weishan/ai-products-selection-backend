package com.example.ssds.ingest.importer;

import java.nio.file.Path;
import java.time.Instant;

/** 尚未確認匯入的暫存檔；token 不含使用者提供的檔名。 */
public record StagedImportFile(String token, Path path, long size, Instant stagedAt) {}
