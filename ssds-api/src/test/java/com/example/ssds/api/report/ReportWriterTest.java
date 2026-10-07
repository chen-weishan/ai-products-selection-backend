package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.api.report.config.ReportProperties;
import com.example.ssds.api.report.model.ReportColumn;
import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.api.report.model.ReportSection;
import com.example.ssds.api.report.service.PdfReportWriter;
import com.example.ssds.api.report.service.XlsxReportWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesXlsxWithChineseContent() throws Exception {
        Path target = temp.resolve("score.xlsx");
        new XlsxReportWriter().write(dataset(), target);

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            assertEquals("品項", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("日式抹茶餅乾", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void embedsAvailableNotoSansTcInPdf() throws Exception {
        Path font = notoFont();
        Assumptions.assumeTrue(font != null, "測試環境未安裝 Noto Sans TC");
        Path target = temp.resolve("weekly.pdf");
        ReportProperties properties = new ReportProperties(
                temp.toString(), 5000, 90, font.toString());

        new PdfReportWriter(properties).write(dataset(), target);

        byte[] bytes = Files.readAllBytes(target);
        assertTrue(bytes.length > 1_000);
        assertEquals("%PDF", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("/FontFile2"), "PDF 必須內嵌 TrueType 字型");
    }

    private ReportDataset dataset() {
        return new ReportDataset(
                "週選品建議",
                "2026W40・四榜各 Top 10",
                List.of(
                        new ReportSection(
                                "話題爆款榜 Top 10",
                                List.of(new ReportColumn("product", "品項"), new ReportColumn("score", "總分")),
                                List.of(Map.of("product", "日式抹茶餅乾", "score", 88.6))),
                        new ReportSection(
                                "月趨勢",
                                List.of(
                                        new ReportColumn("month", "月份"),
                                        new ReportColumn("sample_size", "樣本數"),
                                        new ReportColumn("grade_a_hit_rate", "A 級達標率"),
                                        new ReportColumn("scene_override_rate", "情境覆寫率"),
                                        new ReportColumn("ai_follow_rate", "AI 建議採納率")),
                                List.of(
                                        trend("2026-04", 30, .62, .22, .58),
                                        trend("2026-05", 38, .68, .18, .64),
                                        trend("2026-06", 44, .73, .16, .69),
                                        trend("2026-07", 51, .78, .13, .72),
                                        trend("2026-08", 57, .76, .11, .75),
                                        trend("2026-09", 63, .82, .09, .79)))),
                284);
    }

    private Map<String, Object> trend(
            String month, int sampleSize, double hitRate, double overrideRate, double followRate) {
        return Map.of(
                "month", month,
                "sample_size", sampleSize,
                "grade_a_hit_rate", hitRate,
                "scene_override_rate", overrideRate,
                "ai_follow_rate", followRate);
    }

    private Path notoFont() {
        String configured = System.getenv("PDF_FONT_PATH");
        for (String candidate : new String[] {
                configured,
                "C:/Windows/Fonts/NotoSansTC-VF.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansTC-Regular.otf"
        }) {
            if (candidate == null || candidate.isBlank()) continue;
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path) && Files.isReadable(path)) return path;
        }
        return null;
    }
}
