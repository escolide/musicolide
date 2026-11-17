package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCommandOptionTest {
    @Test
    void monoCenteringRequiresTheExplicitFlag() {
        assertTrue(MusicCommand.isExplicitCenterOption("--center"));
        assertTrue(MusicCommand.isExplicitCenterOption("--CENTER"));
        assertFalse(MusicCommand.isExplicitCenterOption("center"));
        assertFalse(MusicCommand.isExplicitCenterOption("mode:mono"));
    }
}
