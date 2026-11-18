package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeToolchainTest {

    @Test
    void mapsSupportedOperatingSystemsAndArchitectures() {
        assertEquals("linux-x86_64", NativeToolchain.platformKey("Linux", "amd64"));
        assertEquals("linux-aarch64", NativeToolchain.platformKey("Linux", "aarch64"));
        assertEquals("windows-x86_64", NativeToolchain.platformKey("Windows 11", "x86_64"));
        assertEquals("windows-aarch64", NativeToolchain.platformKey("Windows 11", "arm64"));
        assertEquals("macos-aarch64", NativeToolchain.platformKey("Mac OS X", "arm64"));
        assertEquals("macos-x86_64", NativeToolchain.platformKey("Darwin", "x86_64"));
    }

    @Test
    void refusesUnknownPlatformCombinations() {
        assertEquals("unsupported", NativeToolchain.platformKey("FreeBSD", "amd64"));
        assertEquals("unsupported", NativeToolchain.platformKey("Linux", "armv7"));
        assertEquals("unsupported", NativeToolchain.platformKey("Windows 11", "armv7"));
    }

    @Test
    void allowsOnlyExactHostsOrTheirSubdomains() {
        assertTrue(YtDlpDownloader.isHostAllowed("audio.example.org", List.of("example.org")));
        assertTrue(YtDlpDownloader.isHostAllowed("cdn.audio.example.org", List.of("example.org")));
        assertFalse(YtDlpDownloader.isHostAllowed("example.org.attacker.invalid", List.of("example.org")));
        assertFalse(YtDlpDownloader.isHostAllowed("audio.example.org", List.of("other.example.org")));
        assertFalse(YtDlpDownloader.isHostAllowed("audio.example.org", List.of()));
    }

    @Test
    void blocksSpotifyAndSoundCloudButAllowsAdminControlledYouTubeAllowlisting() {
        assertFalse(YtDlpDownloader.isBlockedHost("youtube.com", List.of()));
        assertFalse(YtDlpDownloader.isBlockedHost("music.youtube.com", List.of()));
        assertFalse(YtDlpDownloader.isBlockedHost("youtu.be", List.of()));
        assertTrue(YtDlpDownloader.isBlockedHost("open.spotify.com", List.of("spotify.com")));
        assertTrue(YtDlpDownloader.isBlockedHost("api.soundcloud.com", List.of()));
        assertFalse(YtDlpDownloader.isBlockedHost("audio.example.org", List.of()));
    }

    @Test
    void validatesAuthorizedHttpAudioHosts() throws Exception {
        var uri = YtDlpDownloader.validateSource(
                "https://cdn.example.org/audio/track.flac",
                List.of("example.org"), List.of());
        assertEquals("https", uri.getScheme());
        assertEquals("cdn.example.org", uri.getHost());
    }

    @Test
    void allowsYouTubeOnlyWhenTheAdministratorAllowlistsIt() throws Exception {
        var youtube = YtDlpDownloader.validateSource(
                "https://www.youtube.com/watch?v=example", List.of("youtube.com"), List.of());
        assertEquals("www.youtube.com", youtube.getHost());
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://www.youtube.com/watch?v=example", List.of(), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://open.spotify.com/track/example", List.of("spotify.com"), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://soundcloud.com/artist/track", List.of("soundcloud.com"), List.of()));
    }

    @Test
    void rejectsUnapprovedHostsAndNonHttpUrls() {
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://unknown.example/audio.mp3", List.of("approved.example"), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "file:///etc/passwd", List.of("example.org"), List.of()));
    }

    @Test
    void rejectsCredentialBearingAndPrivateNetworkUrls() {
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "https://user:secret@audio.example.org/song.mp3", List.of("example.org"), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "http://127.0.0.1/private.mp3", List.of("127.0.0.1"), List.of()));
        assertThrows(IOException.class, () -> YtDlpDownloader.validateSource(
                "http://myserver.local/audio.mp3", List.of("myserver.local"), List.of()));
    }
}
