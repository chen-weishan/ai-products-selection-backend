package com.example.ssds.api.imports.dto;

public record ImportPreviewIssue(
        String field,
        String message,
        String type
) {
    public static ImportPreviewIssue error(String field, String message) {
        return new ImportPreviewIssue(field, message, "ERROR");
    }

    public static ImportPreviewIssue duplicate(String field, String message) {
        return new ImportPreviewIssue(field, message, "DUPLICATE");
    }
}
