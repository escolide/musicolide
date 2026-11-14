package io.github.escolide.musicolide.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkNormalizerTest {
    @Test void normalizesSpotifyTrack() {
        assertEquals("spotify:track:abc123",
                LinkNormalizer.normalize("https://open.spotify.com/track/abc123?si=hello").orElseThrow());
    }
    @Test void normalizesYoutubeMusic() {
        assertEquals("youtube:abc123",
                LinkNormalizer.normalize("https://music.youtube.com/watch?v=abc123").orElseThrow());
    }

    @Test void normalizesEquivalentYoutubeUrlsToOneSourceId() {
        assertEquals("youtube:abc123",
                LinkNormalizer.normalize("https://youtu.be/abc123?t=30").orElseThrow());
        assertEquals("youtube:abc123",
                LinkNormalizer.normalize("https://www.youtube.com/watch?v=abc123&list=PL123").orElseThrow());
        assertEquals("youtube:abc123",
                LinkNormalizer.normalize("https://music.youtube.com/watch?v=abc123").orElseThrow());
        assertEquals("youtube:abc123",
                LinkNormalizer.normalize("https://youtube.com/shorts/abc123").orElseThrow());
    }

    @Test void rejectsMalformedUrls() {
        assertTrue(LinkNormalizer.normalize("not a URL").isEmpty());
    }
}
