package com.example.ssds.ingest.importer;

import java.util.LinkedHashSet;
import java.util.Set;

/** 一個可被檔案欄位對應的系統欄位契約。 */
public record ImportSystemField(
        String key,
        String label,
        ImportValueType valueType,
        boolean required,
        Set<String> aliases
) {
    public ImportSystemField {
        aliases = Set.copyOf(new LinkedHashSet<>(aliases));
    }
}
