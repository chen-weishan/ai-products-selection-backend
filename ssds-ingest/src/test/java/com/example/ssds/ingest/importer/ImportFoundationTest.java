package com.example.ssds.ingest.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ssds.core.domain.ImportDataType;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ImportFoundationTest {

    @TempDir
    Path tempDirectory;

    @ParameterizedTest
    @EnumSource(ImportDataType.class)
    void parsesCsvAndXlsxForEveryImportDataType(ImportDataType dataType) throws Exception {
        var registry = new ImportFieldRegistry();
        var required = registry.fieldsFor(dataType).stream().filter(ImportSystemField::required).toList();
        List<String> headers = required.stream().map(ImportSystemField::label).toList();
        List<String> values = required.stream().map(field -> sampleValue(field.valueType())).toList();
        Path csv = tempDirectory.resolve(dataType.name().toLowerCase() + ".csv");
        Files.writeString(csv, String.join(",", headers) + "\n" + String.join(",", values) + "\n",
                StandardCharsets.UTF_8);
        Path xlsx = tempDirectory.resolve(dataType.name().toLowerCase() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("data");
            var headerRow = sheet.createRow(0);
            var valueRow = sheet.createRow(1);
            for (int index = 0; index < headers.size(); index++) {
                headerRow.createCell(index).setCellValue(headers.get(index));
                valueRow.createCell(index).setCellValue(values.get(index));
            }
            try (OutputStream output = Files.newOutputStream(xlsx)) {
                workbook.write(output);
            }
        }
        var parser = new ImportFileParser(
                List.of(new CsvImportReader(), new XlsxImportReader()),
                new ImportHeaderMapper(registry), 200_000);

        for (Path source : List.of(csv, xlsx)) {
            ImportParseResult parsed = parser.parse(source, source.getFileName().toString(), dataType);
            assertThat(parsed.totalRows()).isEqualTo(1);
            assertThat(parsed.headers()).containsExactlyElementsOf(headers);
            assertThat(parsed.suggestions()).extracting(ImportColumnSuggestion::systemField)
                    .containsExactlyElementsOf(required.stream().map(ImportSystemField::key).toList());
        }
    }

    @Test
    void mapsAliasesAndBlocksPersonallyIdentifiableColumns() {
        ImportHeaderMapper mapper = new ImportHeaderMapper(new ImportFieldRegistry());

        List<ImportColumnSuggestion> suggestions = mapper.suggest(
                ImportDataType.SALES,
                List.of("訂單日期", "品名", "瀏覽數", "會員ID", "未知欄位"));

        assertThat(suggestions).extracting(ImportColumnSuggestion::systemField)
                .containsExactly("orderDate", "productName", "impression", null, null);
        assertThat(suggestions.get(3).status())
                .isEqualTo(ImportMappingStatus.BLOCKED_PERSONAL_DATA);
        assertThat(suggestions.get(4).status()).isEqualTo(ImportMappingStatus.UNMAPPED);
    }

    @Test
    void csvReaderCountsAllRowsButKeepsOnlyFirstTwenty() throws Exception {
        Path csv = tempDirectory.resolve("sales.csv");
        List<String> lines = new ArrayList<>();
        lines.add("訂單日期,品名,單價,數量");
        for (int index = 1; index <= 25; index++) {
            lines.add("2026-09-01,奶茶," + index + ",1");
        }
        Files.write(csv, lines, StandardCharsets.UTF_8);

        ImportTabularData result = new CsvImportReader().read(csv, 200_000);

        assertThat(result.headers()).containsExactly("訂單日期", "品名", "單價", "數量");
        assertThat(result.totalRows()).isEqualTo(25);
        assertThat(result.previewRows()).hasSize(20);
    }

    @Test
    void csvReaderStopsAsSoonAsHardRowLimitIsExceeded() throws Exception {
        Path csv = tempDirectory.resolve("too-many.csv");
        Files.writeString(csv, "品名\n一\n二\n三\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new CsvImportReader().read(csv, 2))
                .isInstanceOf(ImportRowLimitExceededException.class)
                .hasMessageContaining("2 列");
    }

    @Test
    void xlsxReaderUsesFirstSheetAndProducesPreview() throws Exception {
        Path xlsx = tempDirectory.resolve("reviews.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("reviews");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("品名");
            header.createCell(1).setCellValue("評論內容");
            for (int index = 1; index <= 3; index++) {
                var row = sheet.createRow(index);
                row.createCell(0).setCellValue("奶茶");
                row.createCell(1).setCellValue("評論 " + index);
            }
            try (OutputStream output = Files.newOutputStream(xlsx)) {
                workbook.write(output);
            }
        }

        ImportTabularData result = new XlsxImportReader().read(xlsx, 200_000);

        assertThat(result.headers()).containsExactly("品名", "評論內容");
        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.previewRows().getFirst()).containsExactly("奶茶", "評論 1");
    }

    @Test
    void xlsxReaderNormalizesNativeDateCellsToIsoDate() throws Exception {
        Path xlsx = tempDirectory.resolve("sales-date.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("sales");
            sheet.createRow(0).createCell(0).setCellValue("訂單日期");
            var dateCell = sheet.createRow(1).createCell(0);
            dateCell.setCellValue(LocalDateTime.of(2026, 9, 8, 0, 0));
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy/m/d"));
            dateCell.setCellStyle(dateStyle);
            try (OutputStream output = Files.newOutputStream(xlsx)) {
                workbook.write(output);
            }
        }

        ImportTabularData result = new XlsxImportReader().read(xlsx, 200_000);

        assertThat(result.previewRows().getFirst().getFirst()).isEqualTo("2026-09-08");
    }

    @Test
    void productMatchingNeverChoosesAnAmbiguousName() {
        ProductMatchingRule rule = new ProductMatchingRule();
        List<ProductMatchingRule.ProductCandidate> candidates = List.of(
                new ProductMatchingRule.ProductCandidate(1L, "測試奶茶", "飲料"),
                new ProductMatchingRule.ProductCandidate(2L, "測試奶茶", "零食"));

        assertThat(rule.match(
                new ProductMatchingRule.ProductMatchInput(null, "測試 奶茶", null), candidates).status())
                .isEqualTo(ProductMatchingRule.ProductMatchStatus.AMBIGUOUS);
        assertThat(rule.match(
                new ProductMatchingRule.ProductMatchInput(null, "測試奶茶", "飲料"), candidates).productId())
                .isEqualTo(1L);
    }

    @Test
    void salesHashNormalizesEquivalentDecimalAndTextValues() {
        String first = SalesDeduplicationKey.sha256(
                LocalDate.of(2026, 9, 1), null, " 測試奶茶 ", "飲 料",
                new BigDecimal("100.00"), 2, 20, "MAIN");
        String second = SalesDeduplicationKey.sha256(
                LocalDate.of(2026, 9, 1), null, "測試奶茶", "飲料",
                new BigDecimal("100"), 2, 20, "main");

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    private String sampleValue(ImportValueType type) {
        return switch (type) {
            case STRING -> "測試資料";
            case INTEGER, LONG -> "10";
            case DECIMAL -> "100.50";
            case DATE -> "2026-09-01";
            case ENUM -> "A";
        };
    }
}
