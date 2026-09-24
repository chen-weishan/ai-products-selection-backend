package com.example.ssds.ai.schema;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 共用數字 token 抽取與精確比對；各 Agent 自行決定允許的輸入來源。 */
public final class NumericTokenValidator {
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_])[-+]?\\d+(?:\\.\\d+)?%?(?![A-Za-z0-9_])");

    private NumericTokenValidator() {}

    public static Set<String> tokensFrom(String... values) {
        Set<String> tokens = new HashSet<>();
        if (values == null) return tokens;
        for (String value : values) {
            if (value == null) continue;
            Matcher matcher = NUMBER.matcher(value);
            while (matcher.find()) tokens.add(matcher.group());
        }
        return tokens;
    }

    public static void add(Set<String> allowed, BigDecimal value) {
        if (value != null) allowed.add(value.toPlainString());
    }

    public static void add(Set<String> allowed, long value) {
        allowed.add(Long.toString(value));
    }

    public static void requireAllowed(String output, Set<String> allowed, String label) {
        Matcher matcher = NUMBER.matcher(output == null ? "" : output);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group())) {
                throw new AiSchemaValidationException(
                        label + " 包含輸入不存在的數字: " + matcher.group());
            }
        }
    }

    public static Set<String> equivalentTokensFrom(String... values) {
        Set<String> result = new HashSet<>();
        for (String token : tokensFrom(values)) result.add(canonical(token));
        return result;
    }

    public static void addEquivalent(Set<String> allowed, BigDecimal value) {
        if (value != null) allowed.add(canonical(value.toPlainString()));
    }

    public static void addEquivalent(Set<String> allowed, long value) {
        allowed.add(canonical(Long.toString(value)));
    }

    public static void requireEquivalent(String output, Set<String> allowed, String label) {
        Matcher matcher = NUMBER.matcher(output == null ? "" : output);
        while (matcher.find()) {
            if (!allowed.contains(canonical(matcher.group()))) {
                throw new AiSchemaValidationException(
                        label + " 包含輸入不存在的數字: " + matcher.group());
            }
        }
    }

    private static String canonical(String token) {
        boolean percent = token.endsWith("%");
        String numeric = percent ? token.substring(0, token.length() - 1) : token;
        try {
            return (percent ? "P:" : "N:")
                    + new BigDecimal(numeric).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            throw new AiSchemaValidationException("無法辨識數字 token: " + token, exception);
        }
    }
}
