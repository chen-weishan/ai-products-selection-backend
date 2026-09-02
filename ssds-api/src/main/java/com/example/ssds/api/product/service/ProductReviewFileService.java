package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.product.dto.ProductReviewFileUploadResponse;
import com.example.ssds.api.product.dto.ProductReviewSummaryResponse;
import com.example.ssds.infra.dao.BulkImportDao;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** S-04 單一品項的評論 CSV 補件。 */
@Service
public class ProductReviewFileService {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_ROWS = 10_000;
    private static final int LOW_CONFIDENCE_THRESHOLD = 20;
    private static final Set<String> CONTENT_HEADERS = Set.of("content", "comment", "評論內容", "評論");
    private static final Set<String> SOURCE_HEADERS = Set.of("source", "來源", "平台");
    private static final Set<String> RATING_HEADERS = Set.of("rating", "評分", "星等");
    private static final Set<String> DATE_HEADERS = Set.of(
            "reviewed_at", "reviewedat", "date", "評論日期", "日期"
    );

    private final ProductRepository productRepository;
    private final ProductReviewRepository reviewRepository;
    private final BulkImportDao bulkImportDao;

    public ProductReviewFileService(
            ProductRepository productRepository,
            ProductReviewRepository reviewRepository,
            BulkImportDao bulkImportDao
    ) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.bulkImportDao = bulkImportDao;
    }

    @Transactional
    public ProductReviewFileUploadResponse upload(Long productId, MultipartFile file) {
        requireProduct(productId);
        validateFile(file);

        List<BulkImportDao.ReviewRow> rows = parse(productId, file);
        int inserted = bulkImportDao.batchInsertReviews(rows);
        long totalReviewCount = reviewRepository.countByProductId(productId);
        return new ProductReviewFileUploadResponse(
                file.getOriginalFilename(),
                rows.size(),
                inserted,
                rows.size() - inserted,
                totalReviewCount,
                totalReviewCount < LOW_CONFIDENCE_THRESHOLD
        );
    }

    @Transactional(readOnly = true)
    public ProductReviewSummaryResponse summary(Long productId) {
        requireProduct(productId);
        long totalReviewCount = reviewRepository.countByProductId(productId);
        return new ProductReviewSummaryResponse(
                totalReviewCount,
                totalReviewCount < LOW_CONFIDENCE_THRESHOLD
        );
    }

    private void requireProduct(Long productId) {
        productRepository.findById(productId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項")
        );
    }

    private void validateFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty()) {
            throw validation("file", "請選擇非空白的 CSV 檔案");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw validation("file", "評論 CSV 不可超過 2 MB");
        }
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw validation("file", "評論檔案必須為 CSV 格式");
        }
    }

    private List<BulkImportDao.ReviewRow> parse(Long productId, MultipartFile file) {
        try {
            String csv = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .get();
            try (CSVParser parser = format.parse(new StringReader(csv))) {
                Map<String, Integer> headers = normalizedHeaders(parser.getHeaderMap());
                Integer contentIndex = findHeader(headers, CONTENT_HEADERS);
                if (contentIndex == null) {
                    throw validation("file", "CSV 必須包含 content（評論內容）欄位");
                }
                Integer sourceIndex = findHeader(headers, SOURCE_HEADERS);
                Integer ratingIndex = findHeader(headers, RATING_HEADERS);
                Integer dateIndex = findHeader(headers, DATE_HEADERS);
                List<BulkImportDao.ReviewRow> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    if (rows.size() >= MAX_ROWS) {
                        throw validation("file", "單次最多匯入 10,000 筆評論");
                    }
                    int rowNumber = Math.toIntExact(record.getRecordNumber() + 1);
                    String content = value(record, contentIndex);
                    if (content.isBlank()) {
                        throw validation("row[" + rowNumber + "].content", "評論內容不可空白");
                    }
                    String source = value(record, sourceIndex);
                    if (source.isBlank()) source = "CSV_SUPPLEMENT";
                    if (source.length() > 50) {
                        throw validation("row[" + rowNumber + "].source", "來源不可超過 50 字");
                    }
                    BigDecimal rating = parseRating(value(record, ratingIndex), rowNumber);
                    LocalDate reviewedAt = parseDate(value(record, dateIndex), rowNumber);
                    rows.add(new BulkImportDao.ReviewRow(
                            productId, source, content, rating, reviewedAt, sha256(content)
                    ));
                }
                if (rows.isEmpty()) {
                    throw validation("file", "CSV 沒有可匯入的評論資料");
                }
                return rows;
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "無法讀取評論 CSV");
        }
    }

    private Map<String, Integer> normalizedHeaders(Map<String, Integer> rawHeaders) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        rawHeaders.forEach((name, index) -> normalized.put(normalizeHeader(name), index));
        return normalized;
    }

    private Integer findHeader(Map<String, Integer> headers, Set<String> aliases) {
        return aliases.stream().map(this::normalizeHeader)
                .filter(headers::containsKey)
                .map(headers::get)
                .findFirst()
                .orElse(null);
    }

    private String normalizeHeader(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private String value(CSVRecord record, Integer index) {
        return index == null || index >= record.size() ? "" : record.get(index).trim();
    }

    private BigDecimal parseRating(String value, int rowNumber) {
        if (value.isBlank()) return null;
        try {
            BigDecimal rating = new BigDecimal(value);
            if (rating.scale() > 1 || rating.compareTo(BigDecimal.ZERO) < 0
                    || rating.compareTo(new BigDecimal("5.0")) > 0) {
                throw new NumberFormatException();
            }
            return rating;
        } catch (NumberFormatException exception) {
            throw validation("row[" + rowNumber + "].rating", "評分必須是 0–5，最多一位小數");
        }
    }

    private LocalDate parseDate(String value, int rowNumber) {
        if (value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw validation("row[" + rowNumber + "].reviewed_at", "評論日期格式必須為 yyyy-MM-dd");
        }
    }

    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BusinessException validation(String field, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldError(field, message))
        );
    }
}
