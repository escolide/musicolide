package io.github.escolide.musicolide.storage;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackRepositorySearchTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void playbackSearchPrioritizesExactTitleThenTitlePrefixThenArtistMatches() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("search.db"));
        MusicTrack artistMatch = track("artist-match", "Jazz Sketch", "Blue Note Ensemble");
        MusicTrack prefixMatch = track("prefix-match", "Blue Note Live", "A Band");
        MusicTrack exactMatch = track("exact-match", "Blue Note", "A Band");

        repository.saveTrack(artistMatch);
        repository.saveTrack(prefixMatch);
        repository.saveTrack(exactMatch);

        List<MusicTrack> results = repository.searchForPlayback("blue note", 10, 0);

        assertEquals(List.of("exact-match", "prefix-match", "artist-match"),
                results.stream().map(MusicTrack::id).toList());
    }

    @Test
    void playbackSearchTreatsUnderscoreAndPercentAsLiteralCharacters() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("wildcards.db"));
        repository.saveTrack(track("literal-underscore", "AC_DC", "Artist"));
        repository.saveTrack(track("wildcard-lookalike", "ACxDC", "Artist"));

        List<MusicTrack> results = repository.searchForPlayback("AC_DC", 10, 0);

        assertEquals(List.of("literal-underscore"), results.stream().map(MusicTrack::id).toList());
    }

    @Test
    void youtubeVideoIdCanBeReadFromTheSavedSourceAlias() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("thumbnail.db"));
        MusicTrack track = track("youtube-track", "Song", "Artist");
        repository.saveTrack(track);
        repository.addAlias("youtube:AbCdEf12345", track.id());

        assertEquals("AbCdEf12345", repository.getYouTubeVideoId(track.id()).orElseThrow());
        assertTrue(repository.getYouTubeVideoId("missing-track").isEmpty());
    }

    private static MusicTrack track(String id, String title, String artist) {
        return new MusicTrack(id, title, artist, "ms_" + id.replace('-', '_'),
                id + ".zip", "0123456789012345678901234567890123456789",
                60_000L, 44_100, 60, 1_024L);
    }
}
