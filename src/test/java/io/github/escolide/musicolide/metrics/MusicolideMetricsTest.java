package io.github.escolide.musicolide.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicolideMetricsTest {
    @Test
    void acceptsOnlyKnownConfigurationCategories() {
        assertEquals("resumable", MetricValue.category(" RESUMABLE ", "segmented", "continuous", "resumable"));
        assertEquals("compact", MetricValue.category("compact", "balanced", "compact", "high", "custom"));
    }

    @Test
    void unknownOrEmptyValuesDoNotTransmitArbitraryConfigurationText() {
        assertEquals("other", MetricValue.category(null, "segmented", "continuous", "resumable"));
        assertEquals("other", MetricValue.category("", "segmented", "continuous", "resumable"));
        assertEquals("other", MetricValue.category("private-server-label", "segmented", "continuous", "resumable"));
    }
}
