package io.github.escolide.musicolide.storage;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackRepositoryPrivatePlaylistTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void privatePlaylistsAreIsolatedByOwnerAndCanBeManaged() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("private-playlists.db"));
        repository.saveTrack(track("first"));
        repository.saveTrack(track("second"));

        String alice = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String bob = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        repository.createPrivatePlaylist(alice, "Mix");
        repository.addPrivatePlaylistTrack(alice, "MIX", "first", 10);
        repository.addPrivatePlaylistTrack(alice, "mix", "second", 10);

        assertTrue(repository.privatePlaylistExists(alice, "mix"));
        assertFalse(repository.privatePlaylistExists(bob, "mix"));
        assertEquals(List.of("mix"), repository.listPrivatePlaylists(alice));
        assertTrue(repository.listPrivatePlaylists(bob).isEmpty());
        assertEquals(List.of("first", "second"), repository.getPrivatePlaylist(alice, "mix", 10)
                .stream().map(MusicTrack::id).toList());
        assertTrue(repository.getPrivatePlaylist(bob, "mix", 10).isEmpty());

        repository.createPrivatePlaylist(bob, "mix");
        repository.addPrivatePlaylistTrack(bob, "mix", "second", 10);
        assertEquals(List.of("second"), repository.getPrivatePlaylist(bob, "mix", 10)
                .stream().map(MusicTrack::id).toList());

        assertTrue(repository.removePrivatePlaylistTrack(alice, "mix", 1));
        assertEquals(List.of("second"), repository.getPrivatePlaylist(alice, "mix", 10)
                .stream().map(MusicTrack::id).toList());
        assertFalse(repository.removePrivatePlaylistTrack(alice, "mix", 2));
        assertTrue(repository.deletePrivatePlaylist(alice, "mix"));
        assertFalse(repository.privatePlaylistExists(alice, "mix"));
        assertTrue(repository.privatePlaylistExists(bob, "mix"));
    }

    @Test
    void privatePlaylistMaximumIsEnforcedAndDuplicateNamesAreOwnerScoped() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("private-limits.db"));
        repository.saveTrack(track("first"));
        repository.saveTrack(track("second"));
        String owner = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        repository.createPrivatePlaylist(owner, "favorites");
        assertThrows(SQLException.class, () -> repository.createPrivatePlaylist(owner, "FAVORITES"));
        repository.addPrivatePlaylistTrack(owner, "favorites", "first", 1);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.addPrivatePlaylistTrack(owner, "favorites", "second", 1));
        assertTrue(error.getMessage().contains("maximum of 1 tracks"));
    }

    @Test
    void sharedPlaylistEntriesCanBeRemovedAndPlaylistDeleted() throws Exception {
        TrackRepository repository = new TrackRepository(temporaryDirectory.resolve("shared-management.db"));
        repository.saveTrack(track("first"));
        repository.saveTrack(track("second"));
        repository.createPlaylist("shared");
        repository.addPlaylistTrack("shared", "first");
        repository.addPlaylistTrack("shared", "second");

        assertTrue(repository.removePlaylistTrack("shared", 1));
        assertEquals(List.of("second"), repository.getPlaylist("shared", 10)
                .stream().map(MusicTrack::id).toList());
        assertTrue(repository.deletePlaylist("shared"));
        assertFalse(repository.playlistExists("shared"));
    }

    private static MusicTrack track(String id) {
        return new MusicTrack(id, id, "", "ms_" + id,
                id + ".zip", "0123456789012345678901234567890123456789",
                60_000L, 44_100, 60, 1_024L);
    }
}
