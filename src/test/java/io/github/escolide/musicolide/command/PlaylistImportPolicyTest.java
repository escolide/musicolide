package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistImportPolicyTest {
    private static final int INTERVAL_SECONDS = 15;
    private static final long INTERVAL_MS = INTERVAL_SECONDS * 1000L;

    @Test
    void longTrackWaitsForConfiguredImportInterval() {
        long now = 100_000L;
        assertFalse(PlaylistImportPolicy.shouldStartImport(
                true, false, 180_000L, 5_000L, now, now + INTERVAL_MS,
                INTERVAL_SECONDS, 0, 0, 2));
        assertTrue(PlaylistImportPolicy.shouldStartImport(
                true, false, 180_000L, 20_000L, now + INTERVAL_MS, now + INTERVAL_MS,
                INTERVAL_SECONDS, 0, 0, 2));
    }

    @Test
    void shortTrackBypassesCooldownAndPreparesNextEntryImmediately() {
        assertTrue(PlaylistImportPolicy.shouldStartImport(
                true, false, 10_000L, 0L, 1000L, 16_000L,
                INTERVAL_SECONDS, 0, 0, 2));
    }

    @Test
    void nearingTrackEndBypassesCooldownToAvoidAnEmptyQueue() {
        assertTrue(PlaylistImportPolicy.shouldStartImport(
                true, false, 180_000L, 170_000L, 1000L, 16_000L,
                INTERVAL_SECONDS, 0, 0, 2));
    }

    @Test
    void waitsUntilPlaybackClockStartsForTheFirstTrack() {
        assertFalse(PlaylistImportPolicy.shouldStartImport(
                false, false, 180_000L, 0L, 1000L, 0L,
                INTERVAL_SECONDS, 0, 0, 2));
    }

    @Test
    void stopsAtTwoFutureTracksAndNeverStartsConcurrentImports() {
        assertFalse(PlaylistImportPolicy.shouldStartImport(
                true, false, 180_000L, 50_000L, 100_000L, 0L,
                INTERVAL_SECONDS, 2, 0, 2));
        assertFalse(PlaylistImportPolicy.shouldStartImport(
                true, false, 180_000L, 50_000L, 100_000L, 0L,
                INTERVAL_SECONDS, 0, 1, 2));
    }

    @Test
    void emptyCurrentTrackStartsImportImmediately() {
        assertTrue(PlaylistImportPolicy.shouldStartImport(
                false, true, 0L, 0L, 1000L, 20_000L,
                INTERVAL_SECONDS, 0, 0, 2));
    }
}
