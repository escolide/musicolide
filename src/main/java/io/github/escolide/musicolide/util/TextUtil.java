package io.github.escolide.musicolide.util;

/** Unicode-code-point-aware text truncation for chat and action-bar labels. */
public final class TextUtil {
    private TextUtil() {}

    public static String truncate(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty() || maximumCodePoints <= 0) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) return value;

        String suffix = "...";
        if (maximumCodePoints <= suffix.length()) return suffix.substring(0, maximumCodePoints);
        int keep = maximumCodePoints - suffix.length();
        int end = value.offsetByCodePoints(0, keep);
        return value.substring(0, end) + suffix;
    }
}
