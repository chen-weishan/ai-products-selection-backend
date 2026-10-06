package com.example.ssds.api.report.service;

import com.example.ssds.api.report.config.ReportProperties;
import com.example.ssds.api.report.model.ReportColumn;
import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.api.report.model.ReportSection;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPageEventHelper;
import java.awt.Color;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class PdfReportWriter {
    private final String fontPath;

    public PdfReportWriter(ReportProperties properties) {
        fontPath = properties.pdfFontPath();
    }

    public void write(ReportDataset dataset, Path target) {
        BaseFont baseFont = embeddedFont();
        Font titleFont = new Font(baseFont, 18, Font.BOLD, new Color(18, 58, 86));
        Font subtitleFont = new Font(baseFont, 10, Font.NORMAL, new Color(74, 90, 103));
        Font sectionFont = new Font(baseFont, 13, Font.BOLD, new Color(18, 58, 86));
        Font bodyFont = new Font(baseFont, 8, Font.NORMAL, Color.BLACK);
        Font headerFont = new Font(baseFont, 8, Font.BOLD, Color.WHITE);

        Document document = new Document(PageSize.A4.rotate(), 28, 28, 30, 30);
        try (OutputStream output = Files.newOutputStream(target)) {
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new FooterPageEvent(bodyFont));
            document.addTitle(dataset.title());
            document.addCreator("莊敬選品決策系統");
            document.open();
            Paragraph title = new Paragraph(dataset.title(), titleFont);
            title.setSpacingAfter(4);
            document.add(title);
            String generatedAt = ZonedDateTime.now(ZoneId.of("Asia/Taipei"))
                    .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
            Paragraph subtitle = new Paragraph(
                    dataset.subtitle() + "\n產生時間：" + generatedAt + "（Asia/Taipei）", subtitleFont);
            subtitle.setSpacingAfter(14);
            document.add(subtitle);
            for (ReportSection section : dataset.sections()) {
                Paragraph heading = new Paragraph(section.title(), sectionFont);
                heading.setSpacingBefore(8);
                heading.setSpacingAfter(6);
                document.add(heading);
                document.add(table(section, bodyFont, headerFont));
                if ("月趨勢".equals(section.title()) && !section.rows().isEmpty()) {
                    document.add(trendChart(writer, section, bodyFont));
                }
            }
            document.close();
        } catch (Exception exception) {
            throw new IllegalStateException("無法產生 PDF 報表", exception);
        }
    }

    private Image trendChart(PdfWriter writer, ReportSection section, Font font) throws Exception {
        float width = 720;
        float height = 180;
        float left = 46;
        float bottom = 28;
        float plotWidth = width - left - 18;
        float plotHeight = height - bottom - 24;
        PdfTemplate chart = writer.getDirectContent().createTemplate(width, height);
        chart.setColorFill(Color.WHITE);
        chart.rectangle(0, 0, width, height);
        chart.fill();
        chart.setColorStroke(new Color(220, 227, 233));
        chart.setLineWidth(0.6f);
        for (int index = 0; index <= 4; index++) {
            float y = bottom + plotHeight * index / 4f;
            chart.moveTo(left, y);
            chart.lineTo(left + plotWidth, y);
        }
        chart.stroke();
        drawSeries(chart, section, "grade_a_hit_rate", new Color(30, 122, 158), left, bottom, plotWidth, plotHeight);
        drawSeries(chart, section, "scene_override_rate", new Color(192, 57, 43), left, bottom, plotWidth, plotHeight);
        drawSeries(chart, section, "ai_follow_rate", new Color(30, 122, 77), left, bottom, plotWidth, plotHeight);
        int size = section.rows().size();
        for (int index = 0; index < size; index++) {
            float x = left + (size == 1 ? plotWidth / 2 : plotWidth * index / (size - 1f));
            Object month = section.rows().get(index).get("month");
            ColumnText.showTextAligned(chart, Element.ALIGN_CENTER,
                    new Phrase(month == null ? "—" : month.toString(), font), x, 8, 0);
        }
        legend(chart, font, 55, 164, new Color(30, 122, 158), "A 級達標率");
        legend(chart, font, 175, 164, new Color(192, 57, 43), "情境覆寫率");
        legend(chart, font, 295, 164, new Color(30, 122, 77), "AI 建議採納率");
        Image image = Image.getInstance(chart);
        image.setSpacingBefore(7);
        image.setSpacingAfter(8);
        return image;
    }

    private void drawSeries(
            PdfContentByte chart, ReportSection section, String key, Color color,
            float left, float bottom, float plotWidth, float plotHeight) {
        chart.setColorStroke(color);
        chart.setLineWidth(1.8f);
        int size = section.rows().size();
        boolean started = false;
        for (int index = 0; index < size; index++) {
            Object raw = section.rows().get(index).get(key);
            if (!(raw instanceof Number number)) continue;
            double rate = Math.max(0, Math.min(1, number.doubleValue()));
            float x = left + (size == 1 ? plotWidth / 2 : plotWidth * index / (size - 1f));
            float y = bottom + (float) rate * plotHeight;
            if (!started) {
                chart.moveTo(x, y);
                started = true;
            } else {
                chart.lineTo(x, y);
            }
        }
        if (started) chart.stroke();
        chart.setColorFill(color);
        for (int index = 0; index < size; index++) {
            Object raw = section.rows().get(index).get(key);
            if (!(raw instanceof Number number)) continue;
            double rate = Math.max(0, Math.min(1, number.doubleValue()));
            float x = left + (size == 1 ? plotWidth / 2 : plotWidth * index / (size - 1f));
            float y = bottom + (float) rate * plotHeight;
            chart.circle(x, y, 2.3f);
            chart.fill();
        }
    }

    private void legend(PdfContentByte chart, Font font, float x, float y, Color color, String label) {
        chart.setColorFill(color);
        chart.rectangle(x, y - 3, 9, 3);
        chart.fill();
        ColumnText.showTextAligned(chart, Element.ALIGN_LEFT, new Phrase(label, font), x + 13, y - 5, 0);
    }

    private PdfPTable table(ReportSection section, Font bodyFont, Font headerFont) {
        PdfPTable table = new PdfPTable(section.columns().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (ReportColumn column : section.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), headerFont));
            cell.setBackgroundColor(new Color(30, 122, 158));
            cell.setPadding(5);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        for (var row : section.rows()) {
            for (ReportColumn column : section.columns()) {
                Object value = row.get(column.key());
                PdfPCell cell = new PdfPCell(new Phrase(value == null ? "—" : value.toString(), bodyFont));
                cell.setPadding(4);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                table.addCell(cell);
            }
        }
        if (section.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("查無資料", bodyFont));
            empty.setColspan(section.columns().size());
            empty.setPadding(12);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(empty);
        }
        return table;
    }

    private BaseFont embeddedFont() {
        Path path = resolveFontPath();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("PDF_FONT_PATH 無法讀取：" + path);
        }
        try {
            FontFactory.register(path.toString(), "Noto Sans TC");
            return BaseFont.createFont(path.toString(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
        } catch (Exception exception) {
            throw new IllegalStateException("無法載入 Noto Sans TC 字型", exception);
        }
    }

    private static class FooterPageEvent extends PdfPageEventHelper {
        private final Font font;

        private FooterPageEvent(Font font) {
            this.font = font;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            String value = "莊敬選品決策系統 · 第 " + writer.getPageNumber() + " 頁";
            ColumnText.showTextAligned(
                    writer.getDirectContent(), Element.ALIGN_CENTER, new Phrase(value, font),
                    (document.left() + document.right()) / 2, 13, 0);
        }
    }

    private Path resolveFontPath() {
        if (!fontPath.isBlank()) {
            return Path.of(fontPath).toAbsolutePath().normalize();
        }
        for (String candidate : new String[] {
                "C:/Windows/Fonts/NotoSansTC-VF.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansTC-Regular.otf",
                "/usr/share/fonts/opentype/noto/NotoSansCJKtc-Regular.otf"
        }) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && Files.isReadable(path)) {
                return path;
            }
        }
        throw new IllegalStateException(
                "PDF_FONT_PATH 未設定且系統找不到 Noto Sans TC；FR-12 要求 PDF 內嵌中文字型");
    }
}
