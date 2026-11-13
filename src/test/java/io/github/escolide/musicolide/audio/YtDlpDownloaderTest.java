package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YtDlpDownloaderTest {
    private static final List<String> YOUTUBE_HOSTS = List.of("youtube.com", "youtu.be");

    @Test
    void acceptsAllowlistedYoutubeWatchAndShortLinks() throws Exception {
        URI watch = YtDlpDownloader.validateSource(
                "https://www.youtube.com/watch?v=example123", YOUTUBE_HOSTS, List.of());
        URI shortLink = YtDlpDownloader.validateSource(
                "https://youtu.be/example123", YOUTUBE_HOSTS, List.of());

        assertEquals("www.youtube.com", watch.getHost());
        assertEquals("youtu.be", shortLink.getHost());
    }

    @Test
    void acceptsYoutubeSubdomainsButNotLookalikeDomains() throws Exception {
        URI music = YtDlpDownloader.validateSource(
                "https://music.youtube.com/watch?v=example123", YOUTUBE_HOSTS, List.of());

        assertEquals("music.youtube.com", music.getHost());
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://youtube.com.attacker.example/watch?v=example123", YOUTUBE_HOSTS, List.of()));
    }

    @Test
    void refusesSourcesWhenNoHostWasAllowlisted() {
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://www.youtube.com/watch?v=example123", List.of(), List.of()));
    }

    @Test
    void alwaysBlocksMetadataOnlyPlatformsEvenWhenAllowlisted() {
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://open.spotify.com/track/example", List.of("spotify.com"), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://soundcloud.com/artist/track", List.of("soundcloud.com"), List.of()));
    }

    @Test
    void rejectsCredentialsUnsupportedSchemesAndPrivateAddresses() {
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://user:password@youtube.com/watch?v=example123", YOUTUBE_HOSTS, List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "file:///etc/passwd", List.of("youtube.com"), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://127.0.0.1/audio", List.of("127.0.0.1"), List.of()));
    }
}
