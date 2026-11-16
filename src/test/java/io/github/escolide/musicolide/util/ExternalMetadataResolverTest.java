package io.github.escolide.musicolide.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMetadataResolverTest {
    private final ExternalMetadataResolver resolver = new ExternalMetadataResolver();

    @Test
    void ignoresUnsupportedHostsWithoutIssuingRequests() {
        assertTrue(resolver.resolve("https://example.invalid/track").join().isEmpty());
    }

    @Test
    void rejectsOversizedSourceUrlsBeforeIssuingRequests() {
        String oversized = "https://youtube.com/watch?v=" + "x".repeat(2100);
        assertTrue(resolver.resolve(oversized).join().isEmpty());
    }

    @Test
    void rejectsMalformedUrlsWithoutIssuingRequests() {
        assertTrue(resolver.resolve("not a valid URL").join().isEmpty());
        assertTrue(resolver.resolve(null).join().isEmpty());
    }
}
