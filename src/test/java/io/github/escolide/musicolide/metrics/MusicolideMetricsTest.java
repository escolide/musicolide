package io.github.escolide.musicolide.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicolideMetricsTest {
    @Test
    void acceptsOnlyKnownConfigurationCategories() {
        assertEquals("resumable", MusicolideMetrics.category(" RESUMABLE ", "segmented", "continuous", "resumable"));
        assertEquals("compact", MusicolideMetrics.category("compact", "balanced", "compact", "high", "custom"));
    }

    @Test
    void unknownOrEmptyValuesDoNotTransmitArbitraryConfigurationText() {
        assertEquals("other", MusicolideMetrics.category(null, "segmented", "continuous", "resumable"));
        assertEquals("other", MusicolideMetrics.category("", "segmented", "continuous", "resumable"));
        assertEquals("other", MusicolideMetrics.category("private-server-label", "segmented", "continuous", "resumable"));
    }
}
