package com.example.ssds.ingest.importer;

import java.util.List;

record ImportTabularData(List<String> headers, List<List<String>> previewRows, int totalRows) {}
