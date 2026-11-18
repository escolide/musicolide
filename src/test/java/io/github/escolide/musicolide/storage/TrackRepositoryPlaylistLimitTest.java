package io.github.escolide.musicolide.storage;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackRepositoryPlaylistLimitTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addingTracksCannotExceedConfiguredPlaylistLimit() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("playlist-limit.db"));
        repository.saveTrack(track("first"));
        repository.saveTrack(track("second"));
        repository.createPlaylist("mix");

        repository.addPlaylistTrack("mix", "first", 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.addPlaylistTrack("mix", "second", 1));
        assertEquals("Playlist has reached its maximum of 1 tracks. Remove a track before adding another.",
                error.getMessage());
        assertEquals(1, repository.getPlaylist("mix", 20).size());
        assertEquals("first", repository.getPlaylist("mix", 20).get(0).id());
    }

    @Test
    void legacyAddMethodRemainsAvailable() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("legacy-playlist.db"));
        repository.saveTrack(track("first"));
        repository.createPlaylist("legacy");

        repository.addPlaylistTrack("legacy", "first");

        assertEquals(1, repository.getPlaylist("legacy", 20).size());
    }

    private static MusicTrack track(String id) {
        return new MusicTrack(id, id, "", "ms_" + id,
                id + ".zip", "0123456789012345678901234567890123456789",
                60_000L, 44_100, 60, 1_024L);
    }
}
