package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpDownloaderTest {
    private static final List<String> YOUTUBE_HOSTS = List.of("youtube.com", "youtu.be");

    @Test
    void singleVideoCommandDoesNotUseMaxDownloadsLimitExitCode() {
        List<String> command = YtDlpDownloader.buildCommand(
                "yt-dlp",
                URI.create("https://youtu.be/example123"),
                "qjs",
                Path.of("cache/yt-dlp"),
                100,
                1800,
                Path.of("incoming/source.%(ext)s"));

        assertTrue(command.contains("--no-playlist"));
        assertTrue(command.contains("--js-runtimes"));
        assertTrue(command.contains("quickjs:qjs"));
        assertTrue(command.contains("--remote-components"));
        assertTrue(command.contains("ejs:github"));
        assertFalse(command.contains("--max-downloads"));
        assertEquals("https://youtu.be/example123", command.get(command.size() - 1));
    }

    @Test
    void youtubeCommandRequiresJavascriptRuntime() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                YtDlpDownloader.buildCommand(
                        "yt-dlp",
                        URI.create("https://youtu.be/example123"),
                        null,
                        Path.of("cache/yt-dlp"),
                        100,
                        1800,
                        Path.of("incoming/source.%(ext)s")));
    }

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
