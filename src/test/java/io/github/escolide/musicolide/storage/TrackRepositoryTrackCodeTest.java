package io.github.escolide.musicolide.storage;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class TrackRepositoryTrackCodeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void assignsStableShortCodesAndResolvesTracksByCode() throws Exception {
        Path database = temporaryDirectory.resolve("musicolide.db");
        MusicTrack first = track("legacy-internal-one");
        MusicTrack second = track("legacy-internal-two");

        try (TrackRepository repository = new TrackRepository(database)) {
            repository.saveTrack(first);
            repository.saveTrack(second);

            assertEquals("a00", repository.getTrackCode(first.id()).orElseThrow());
            assertTrue(repository.getTrackCode(first.id()).orElseThrow().matches("[a-f][0-9]{2}"));
            assertEquals("a01", repository.getTrackCode(second.id()).orElseThrow());
            assertEquals(first, repository.getTrack("a00").orElseThrow());
            assertEquals(second, repository.getTrack("a01").orElseThrow());
            assertEquals(first, repository.searchForPlayback("a00", 5, 0).getFirst());
        }

        try (TrackRepository reopened = new TrackRepository(database)) {
            assertEquals("a00", reopened.getTrackCode(first.id()).orElseThrow());
            assertEquals(first, reopened.getTrack("a00").orElseThrow());
        }
    }

    @Test
    void migratesExistingRowsAndKeepsDeletedTrackIDsReserved() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE tracks (
                      id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL,
                      namespace TEXT NOT NULL, pack_file TEXT NOT NULL, sha1_hex TEXT NOT NULL,
                      duration_ms INTEGER NOT NULL, segment_ms INTEGER NOT NULL,
                      segment_count INTEGER NOT NULL, pack_size_bytes INTEGER NOT NULL,
                      created_at INTEGER NOT NULL
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, "old-track");
                insert.setString(2, "Old Track");
                insert.setString(3, "Old Artist");
                insert.setString(4, "ms_old_track");
                insert.setString(5, "old.zip");
                insert.setString(6, "0123456789012345678901234567890123456789");
                insert.setLong(7, 60_000L);
                insert.setInt(8, 60_000);
                insert.setInt(9, 1);
                insert.setLong(10, 1024L);
                insert.setLong(11, 1L);
                insert.executeUpdate();
            }
        }

        try (TrackRepository repository = new TrackRepository(database)) {
            MusicTrack migrated = repository.getTrack("old-track").orElseThrow();
            assertEquals("a00", repository.getTrackCode(migrated.id()).orElseThrow());
            assertEquals(migrated, repository.getTrack("a00").orElseThrow());
            assertTrue(repository.deleteTrack("a00"));
            assertEquals("a00", repository.getTrackCode(migrated.id()).orElseThrow(),
                    "A deleted TrackID remains reserved so it cannot identify another song later.");
            assertTrue(repository.getTrack("a00").isEmpty());
            MusicTrack next = track("new-track");
            repository.saveTrack(next);
            assertEquals("a01", repository.getTrackCode(next.id()).orElseThrow());
        }
    }

    private static MusicTrack track(String id) {
        return new MusicTrack(id, "Test " + id, "Test Artist", "ms_" + id.replace('-', '_'),
                id + ".zip", "0123456789012345678901234567890123456789",
                60_000L, 60_000, 1, 1024L);
    }
}
