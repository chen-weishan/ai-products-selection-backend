package com.example.ssds.api.imports.dto;

import java.util.List;
import java.util.Map;

public record ImportPreviewRow(
        int rowNumber,
        Map<String, String> values,
        List<ImportPreviewIssue> issues
) {}
