package com.example.ssds.ai.prompt;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Agent 6 的資料庫查詢、Prompt 與快取鍵共用同一套關鍵字正規化規則。 */
public final class SourcingKeywordNormalizer {
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern BETWEEN_HAN_CHARACTERS =
            Pattern.compile("(?<=\\p{IsHan}) (?=\\p{IsHan})");

    private SourcingKeywordNormalizer() {}

    public static String normalize(String keyword) {
        if (keyword == null) return "";
        String compatibilityNormalized = Normalizer.normalize(keyword, Normalizer.Form.NFKC);
        String whitespaceNormalized = WHITESPACE.matcher(compatibilityNormalized)
                .replaceAll(" ")
                .strip();
        return BETWEEN_HAN_CHARACTERS.matcher(whitespaceNormalized)
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }
}
