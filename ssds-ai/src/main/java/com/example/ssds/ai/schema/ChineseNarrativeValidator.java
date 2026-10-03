package com.example.ssds.ai.schema;

import java.util.Collection;
import java.util.regex.Pattern;

/** Validates user-facing AI prose without rejecting legitimate English product or brand names. */
public final class ChineseNarrativeValidator {
    private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");

    private ChineseNarrativeValidator() {}

    public static void requireReadableChinese(
            String text, String field, Collection<String> internalTerms) {
        if (!HAN_CHARACTER.matcher(text).find()) {
            fail(field + " 必須使用繁體中文敘述");
        }
        for (String term : internalTerms) {
            if (containsTerm(text, term)) {
                fail(field + " 不得包含內部名稱: " + term);
            }
        }
    }

    private static boolean containsTerm(String text, String term) {
        Pattern token = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(term) + "(?![A-Za-z0-9_])");
        return token.matcher(text).find();
    }

    private static void fail(String message) {
        throw new AiSchemaValidationException(message);
    }
}
