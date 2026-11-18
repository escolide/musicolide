package io.github.escolide.musicolide.playback;

/** Selects how a mono sound is anchored for an individual listener. */
public final class PlaybackSoundPolicy {
    private PlaybackSoundPolicy() {}

    /**
     * Ordinary mono is attached to the listener entity so the client follows
     * movement. The explicit centered-origin option remains fixed at the zone.
     */
    public static boolean followsListener(boolean mono, boolean centeredAudioOrigin) {
        return mono && !centeredAudioOrigin;
    }
}
