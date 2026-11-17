package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCommandOptionTest {
    @Test
    void monoCenteringRequiresTheExplicitFlag() {
        assertTrue(PlayTargetDefaults.isExplicitCenterOption("--center"));
        assertTrue(PlayTargetDefaults.isExplicitCenterOption("--CENTER"));
        assertFalse(PlayTargetDefaults.isExplicitCenterOption("center"));
        assertFalse(PlayTargetDefaults.isExplicitCenterOption("mode:mono"));
    }
}
