package com.example.ssds.api.imports.dto;

import com.example.ssds.ingest.importer.ImportSystemField;
import com.example.ssds.ingest.importer.ImportValueType;

public record ImportFieldResponse(
        String key,
        String label,
        ImportValueType valueType,
        boolean required
) {
    public static ImportFieldResponse from(ImportSystemField field) {
        return new ImportFieldResponse(
                field.key(), field.label(), field.valueType(), field.required());
    }
}
