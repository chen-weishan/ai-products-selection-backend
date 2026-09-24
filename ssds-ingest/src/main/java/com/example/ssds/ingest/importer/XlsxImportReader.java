package com.example.ssds.ingest.importer;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.util.XMLHelper;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * XLSX 事件式 reader。只讀第一個工作表，解析超過 200,000 列時可立刻中止，
 * 不會像 WorkbookFactory 一樣把整份活頁簿展開到記憶體。
 */
@Component
class XlsxImportReader implements ImportTabularReader {

    @Override
    public boolean supports(ImportFileFormat format) {
        return format == ImportFileFormat.XLSX;
    }

    @Override
    public void scan(Path path, int hardMaxRows, ImportSheetHandler handler) {
        try (OPCPackage opcPackage = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            XSSFReader workbook = new XSSFReader(opcPackage);
            Iterator<InputStream> sheets = workbook.getSheetsData();
            if (!sheets.hasNext()) {
                throw new ImportFileParseException("XLSX 沒有可讀取的工作表");
            }
            StylesTable styles = workbook.getStylesTable();
            SharedStrings sharedStrings = workbook.getSharedStringsTable();
            SheetHandler sheetHandler = new SheetHandler(hardMaxRows, handler);
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(
                    styles,
                    null,
                    sharedStrings,
                    sheetHandler,
                    new IsoDateDataFormatter(),
                    false));
            try (InputStream sheet = sheets.next()) {
                parser.parse(new InputSource(sheet));
            }
            sheetHandler.requireHeaders();
        } catch (ImportRowLimitExceededException | ImportFileParseException
                 | ImportFileScanner.ImportHeaderCapturedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ImportFileParseException("XLSX 檔案無法解析", exception);
        }
    }

    /** Excel 原生日期儲存格統一輸出 ISO-8601，避免顯示格式影響後續型別驗證。 */
    private static final class IsoDateDataFormatter extends DataFormatter {
        private IsoDateDataFormatter() {
            super(Locale.TAIWAN);
        }

        @Override
        public String formatRawCellContents(
                double value,
                int formatIndex,
                String formatString,
                boolean use1904Windowing
        ) {
            if (DateUtil.isADateFormat(formatIndex, formatString)) {
                return DateUtil.getLocalDateTime(value, use1904Windowing)
                        .toLocalDate().toString();
            }
            return super.formatRawCellContents(value, formatIndex, formatString, use1904Windowing);
        }
    }

    private static final class SheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int hardMaxRows;
        private final ImportSheetHandler delegate;
        private List<String> headers;
        private List<String> currentRow;
        private int currentColumn;
        private int totalRows;

        private SheetHandler(int hardMaxRows, ImportSheetHandler delegate) {
            this.hardMaxRows = hardMaxRows;
            this.delegate = delegate;
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = new ArrayList<>();
            currentColumn = 0;
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRow.stream().allMatch(String::isBlank)) {
                return;
            }
            if (headers == null) {
                headers = List.copyOf(currentRow);
                validateHeaders(headers);
                delegate.onHeaders(headers);
                return;
            }
            totalRows++;
            if (totalRows > hardMaxRows) {
                throw new ImportRowLimitExceededException(hardMaxRows);
            }
            List<String> aligned = new ArrayList<>(headers.size());
            for (int index = 0; index < headers.size(); index++) {
                aligned.add(index < currentRow.size() ? currentRow.get(index) : "");
            }
            delegate.onRow(rowNum + 1, List.copyOf(aligned));
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = columnIndex(cellReference);
            while (currentColumn < column) {
                currentRow.add("");
                currentColumn++;
            }
            currentRow.add(formattedValue == null ? "" : formattedValue.trim());
            currentColumn++;
        }

        private void requireHeaders() {
            if (headers == null) {
                throw new ImportFileParseException("檔案必須包含標題列");
            }
        }

        private static int columnIndex(String cellReference) {
            if (cellReference == null) {
                return 0;
            }
            int column = 0;
            int index = 0;
            while (index < cellReference.length() && Character.isLetter(cellReference.charAt(index))) {
                column = column * 26 + Character.toUpperCase(cellReference.charAt(index)) - 'A' + 1;
                index++;
            }
            return Math.max(0, column - 1);
        }

        private static void validateHeaders(List<String> headers) {
            Set<String> unique = new HashSet<>();
            for (String header : headers) {
                String normalized = ImportHeaderMapper.normalize(header);
                if (normalized.isBlank()) {
                    throw new ImportFileParseException("標題列不可包含空白欄位名稱");
                }
                if (!unique.add(normalized)) {
                    throw new ImportFileParseException("標題列包含重複欄位：" + header);
                }
            }
        }
    }
}
