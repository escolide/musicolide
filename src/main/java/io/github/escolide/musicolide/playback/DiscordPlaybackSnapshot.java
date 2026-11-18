package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicTrack;

import java.util.UUID;

/**
 * Immutable view of the one Musicolide session selected for the configured
 * Discord voice channel. It is read-only: Discord never receives a reference
 * that can mutate a Minecraft playback session.
 */
public record DiscordPlaybackSnapshot(
        UUID sessionId,
        long timelineGeneration,
        MusicTrack track,
        long elapsedMs,
        boolean paused,
        boolean preparing,
        boolean clockStarted,
        double speed,
        boolean mono
) {
    public boolean isAudible() {
        return track != null && clockStarted && !paused && !preparing;
    }
}
