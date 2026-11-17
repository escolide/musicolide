package io.github.escolide.musicolide.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSoundPolicyTest {
    @Test
    void ordinaryMonoFollowsTheListenerButExplicitCenterDoesNot() {
        assertTrue(PlaybackSoundPolicy.followsListener(true, false));
        assertFalse(PlaybackSoundPolicy.followsListener(true, true));
    }

    @Test
    void stereoOriginPolicyRemainsUnchanged() {
        assertFalse(PlaybackSoundPolicy.followsListener(false, false));
        assertFalse(PlaybackSoundPolicy.followsListener(false, true));
    }
}
