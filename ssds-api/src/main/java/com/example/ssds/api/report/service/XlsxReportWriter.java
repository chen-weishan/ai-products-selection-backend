package com.example.ssds.api.report.service;

import com.example.ssds.api.report.model.ReportColumn;
import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.api.report.model.ReportSection;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class XlsxReportWriter {

    public void write(ReportDataset dataset, Path target) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
                OutputStream output = Files.newOutputStream(target)) {
            workbook.setCompressTempFiles(true);
            CellStyle header = headerStyle(workbook);
            int sectionIndex = 1;
            for (ReportSection section : dataset.sections()) {
                Sheet sheet = workbook.createSheet(sheetName(section.title(), sectionIndex++));
                writeSection(sheet, section, header);
            }
            workbook.write(output);
        } catch (IOException exception) {
            throw new IllegalStateException("無法產生 XLSX 報表", exception);
        }
    }

    private void writeSection(Sheet sheet, ReportSection section, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int index = 0; index < section.columns().size(); index++) {
            Cell cell = header.createCell(index);
            cell.setCellValue(section.columns().get(index).label());
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(index, Math.min(50, Math.max(14,
                    section.columns().get(index).label().length() * 2 + 4)) * 256);
        }
        int rowIndex = 1;
        for (var values : section.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int index = 0; index < section.columns().size(); index++) {
                ReportColumn column = section.columns().get(index);
                writeCell(row.createCell(index), values.get(column.key()));
            }
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, Math.max(0, rowIndex - 1), 0, Math.max(0, section.columns().size() - 1)));
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof Date date) {
            cell.setCellValue(date);
        } else if (value instanceof TemporalAccessor) {
            cell.setCellValue(value.toString());
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private String sheetName(String title, int index) {
        String cleaned = title.replaceAll("[\\\\/?*\\[\\]:]", "-");
        String candidate = index + "-" + cleaned;
        return candidate.substring(0, Math.min(31, candidate.length()));
    }
}
