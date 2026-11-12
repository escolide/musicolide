package io.github.escolide.musicolide.storage;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackRepositoryRetentionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void temporaryTrackExpiresOnlyAfterItsExpiryTimeAndDeleteCascadesAliases() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("musicolide.db"));
        MusicTrack track = track("temporary-track");

        repository.saveTemporaryTrack(track, 2_000L);
        repository.addAlias("https://media.example/track/one", track.id());

        assertTrue(repository.expiredTemporaryTracks(1_999L).isEmpty());
        assertEquals(List.of(track), repository.expiredTemporaryTracks(2_000L));
        assertEquals(track, repository.resolveAlias("https://media.example/track/one").orElseThrow());

        assertTrue(repository.deleteTrack(track.id()));
        assertTrue(repository.getTrack(track.id()).isEmpty());
        assertTrue(repository.resolveAlias("https://media.example/track/one").isEmpty());
        assertTrue(repository.expiredTemporaryTracks(3_000L).isEmpty());
    }

    @Test
    void ordinaryFileImportsDoNotBecomeTemporary() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("musicolide.db"));
        MusicTrack track = track("permanent-track");

        repository.saveTrack(track);

        assertTrue(repository.expiredTemporaryTracks(Long.MAX_VALUE).isEmpty());
        assertEquals(track, repository.getTrack(track.id()).orElseThrow());
    }

    private static MusicTrack track(String id) {
        return new MusicTrack(id, "Test Track", "", "mc_" + id,
                id + ".zip", "0123456789012345678901234567890123456789",
                60_000L, 1_000, 60, 1_024L);
    }
}
