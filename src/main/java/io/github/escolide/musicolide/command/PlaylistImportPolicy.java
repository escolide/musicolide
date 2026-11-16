package io.github.escolide.musicolide.command;

/**
 * Pure scheduling policy for staggered URL-playlist imports.
 * Server-side importing may stay ahead, but this policy never controls
 * player-side resource-pack delivery.
 */
final class PlaylistImportPolicy {
    private PlaylistImportPolicy() {}

    static boolean shouldStartImport(boolean clockStarted,
                                     boolean currentMissing,
                                     long currentDurationMs,
                                     long elapsedMs,
                                     long nowMillis,
                                     long nextImportAtMillis,
                                     int intervalSeconds,
                                     int queuedTracks,
                                     int inFlightImports,
                                     int maximumAhead) {
        if (inFlightImports > 0 || queuedTracks + inFlightImports >= Math.max(1, maximumAhead)) {
            return false;
        }
        if (currentMissing) return true;
        if (!clockStarted) return false;

        long intervalMs = Math.max(0L, intervalSeconds) * 1000L;
        long durationMs = Math.max(0L, currentDurationMs);
        long remainingMs = Math.max(0L, durationMs - Math.max(0L, elapsedMs));

        // Short tracks bypass the spacing window. Also bypass it near the end
        // when the remaining import window is no longer long enough to risk a gap.
        boolean shortTrack = durationMs > 0L && durationMs <= intervalMs;
        boolean urgent = remainingMs <= intervalMs;
        return shortTrack || urgent || nowMillis >= nextImportAtMillis;
    }
}
