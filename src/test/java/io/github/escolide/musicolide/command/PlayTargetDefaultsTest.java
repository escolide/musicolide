package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayTargetDefaultsTest {
    @Test
    void spatialRequestsDefaultToAllPlayersAndLetTheZoneFilterAudience() {
        assertEquals("@a", PlayTargetDefaults.defaultSelector(true, true));
    }

    @Test
    void nonSpatialPlayerRequestsRemainSelfOnly() {
        assertEquals("@s", PlayTargetDefaults.defaultSelector(true, false));
    }

    @Test
    void consoleMustSpecifyAnExplicitAudience() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PlayTargetDefaults.defaultSelector(false, true));
        assertTrue(error.getMessage().contains("specify a target"));
    }
}
