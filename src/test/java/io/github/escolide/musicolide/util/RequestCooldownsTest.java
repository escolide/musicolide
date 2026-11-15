package io.github.escolide.musicolide.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestCooldownsTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void enforcesActorAndGlobalCooldownsWithoutExtendingRejectedRequests() {
        RequestCooldowns cooldowns = new RequestCooldowns();

        assertEquals(0L, cooldowns.tryAcquireAt("import", "alice", 10, 2, 100L));
        assertEquals(10L, cooldowns.tryAcquireAt("import", "alice", 10, 2, 100L + 1L));
        assertEquals(2L, cooldowns.tryAcquireAt("import", "bob", 10, 2, 100L + 1L));
        assertEquals(0L, cooldowns.tryAcquireAt("import", "bob", 10, 2, 100L + 2L * SECOND));
        assertEquals(8L, cooldowns.tryAcquireAt("import", "alice", 10, 2, 100L + 2L * SECOND));
    }

    @Test
    void zeroCooldownsDisableThrottlingAndDifferentActionsAreIndependent() {
        RequestCooldowns cooldowns = new RequestCooldowns();

        assertEquals(0L, cooldowns.tryAcquireAt("search", "alice", 0, 0, 100L));
        assertEquals(0L, cooldowns.tryAcquireAt("search", "alice", 0, 0, 101L));
        assertEquals(0L, cooldowns.tryAcquireAt("import", "alice", 10, 2, 101L));
    }
}
