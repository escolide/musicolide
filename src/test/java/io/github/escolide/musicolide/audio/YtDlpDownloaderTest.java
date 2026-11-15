package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        assertTrue(command.contains("--write-info-json"));
        assertTrue(command.contains("--js-runtimes"));
        assertTrue(command.contains("quickjs:qjs"));
        assertTrue(command.contains("--remote-components"));
        assertTrue(command.contains("ejs:github"));
        assertFalse(command.contains("--max-downloads"));
        assertEquals("https://youtu.be/example123", command.get(command.size() - 1));
    }


    @Test
    void searchCommandOnlyFetchesMetadataAndUsesBoundedYoutubeSearch() {
        List<String> command = YtDlpDownloader.buildSearchCommand(
                "yt-dlp", "qjs", Path.of("cache/yt-dlp"), 18, "Daft Punk One More Time");

        assertTrue(command.contains("--flat-playlist"));
        assertTrue(command.contains("--skip-download"));
        assertTrue(command.contains("--dump-single-json"));
        assertTrue(command.contains("--playlist-end"));
        assertFalse(command.contains("--format"));
        assertFalse(command.contains("--write-info-json"));
        assertEquals("ytsearch18:Daft Punk One More Time",
                command.get(command.indexOf("--") + 1));
    }

    @Test
    void searchCommandSupportsLaterPagesBeyondTheOldThirtyResultCap() {
        List<String> command = YtDlpDownloader.buildSearchCommand(
                "yt-dlp", "qjs", Path.of("cache/yt-dlp"), 48, "artist album");
        int playlistEnd = command.indexOf("--playlist-end");
        assertTrue(playlistEnd >= 0);
        assertEquals("48", command.get(playlistEnd + 1));
        assertEquals("ytsearch48:artist album", command.get(command.size() - 1));
    }

    @Test
    void parserAcceptsSingleVideoResponseWhenPlaylistWrapperIsAbsent() throws Exception {
        String json = """
                {"id":"single1234","title":"A Single Result","uploader":"Artist","duration":210}
                """;
        List<YtDlpDownloader.SearchResult> results = YtDlpDownloader.parseSearchResults(json, 6, false);
        assertEquals(1, results.size());
        assertEquals("single1234", results.get(0).videoId());
    }

    @Test
    void parserAllowsMoreThanTwentyResultsForOnDemandPages() throws Exception {
        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < 36; i++) {
            if (i > 0) json.append(',');
            json.append("{\"id\":\"video").append(String.format("%04d", i))
                    .append("\",\"title\":\"Track ").append(i).append("\",\"duration\":200}");
        }
        json.append("]}");
        assertEquals(36, YtDlpDownloader.parseSearchResults(json.toString(), 36, false).size());
    }

    @Test
    void songPriorityPrefersOfficialAudioButCanBeDisabled() throws Exception {
        String json = """
                {
                  "entries": [
                    {"id":"tutorial1234","title":"How to play One More Time - Tutorial","uploader":"Piano Lessons","duration":240},
                    {"id":"official123","title":"One More Time (Official Audio)","uploader":"Artist - Topic","duration":225},
                    {"id":"reaction123","title":"One More Time Reaction","uploader":"React Channel","duration":300}
                  ]
                }
                """;

        List<YtDlpDownloader.SearchResult> prioritized =
                YtDlpDownloader.parseSearchResults(json, 2, true);
        List<YtDlpDownloader.SearchResult> originalOrder =
                YtDlpDownloader.parseSearchResults(json, 2, false);

        assertEquals("official123", prioritized.get(0).videoId());
        assertEquals("tutorial1234", originalOrder.get(0).videoId());
        assertEquals(2, prioritized.size());
    }

    @Test
    void parsesMultilingualTitleArtistAndYoutubeSourceIdFromInfoJson(@org.junit.jupiter.api.io.TempDir Path temporary)
            throws Exception {
        Path info = temporary.resolve("source.info.json");
        String json = """
                {
                  "title": "夜に駆ける / مرحبا",
                  "artist": ["YOASOBI", "ضيف"],
                  "id": "abc123",
                  "extractor_key": "Youtube"
                }
                """;
        Files.writeString(info, json, StandardCharsets.UTF_8);

        YtDlpDownloader.Metadata metadata = YtDlpDownloader.readMetadata(info);

        assertEquals("夜に駆ける / مرحبا", metadata.title());
        assertEquals("YOASOBI, ضيف", metadata.artist());
        assertEquals("abc123", metadata.sourceId());
        assertEquals("Youtube", metadata.extractor());
    }

    @Test
    void missingOrMalformedInfoJsonDoesNotPreventFallbackMetadata() throws Exception {
        Path missing = temporaryPath("missing-info");
        assertEquals("", YtDlpDownloader.readMetadata(missing).title());

        Path malformed = temporaryPath("malformed-info");
        Files.writeString(malformed, "{not valid json", StandardCharsets.UTF_8);
        assertEquals("", YtDlpDownloader.readMetadata(malformed).title());
    }

    private Path temporaryPath(String name) throws IOException {
        Path directory = java.nio.file.Files.createTempDirectory("musicolide-ytdlp-test-");
        return directory.resolve(name + ".info.json");
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
