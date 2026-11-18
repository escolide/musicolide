package io.github.escolide.musicolide.metrics;

import java.util.Locale;

/** Sanitizes configuration values before they are submitted as bStats categories. */
final class MetricValue {
    private MetricValue() {}

    static String category(String raw, String... allowedValues) {
        if (raw == null) return "other";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (String allowed : allowedValues) {
            if (allowed.equals(normalized)) return normalized;
        }
        return "other";
    }
}
