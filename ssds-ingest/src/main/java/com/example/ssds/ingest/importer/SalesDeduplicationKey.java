package com.example.ssds.ingest.importer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * SALES 的內容雜湊規則。相同日期、品項、類別、單價、數量、曝光與客群視為重複；
 * 先在單一檔案內去重，後續寫入階段可用同一個 key 與既有資料比對。
 */
public final class SalesDeduplicationKey {

    private SalesDeduplicationKey() {}

    public static String sha256(
            LocalDate orderDate,
            Long matchedProductId,
            String productName,
            String category,
            BigDecimal price,
            Integer qty,
            Integer impression,
            String audienceCode
    ) {
        String productKey = matchedProductId == null
                ? "name:" + normalize(productName)
                : "id:" + matchedProductId;
        String canonical = String.join("|",
                value(orderDate),
                productKey,
                normalize(category),
                decimal(price),
                value(qty),
                value(impression),
                normalize(audienceCode));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支援 SHA-256", exception);
        }
    }

    private static String normalize(String value) {
        return ImportHeaderMapper.normalize(value);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
