package io.github.escolide.musicolide.geyser;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPackReadinessTest {
    @Test
    void offeredPackWaitsUntilGeyserReportsPlayReady() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        UUID player = UUID.randomUUID();

        readiness.offered(player, "revision-1");

        assertTrue(readiness.isAwaiting(player, "revision-1"));
        assertFalse(readiness.isReady(player, "revision-1"));
        assertFalse(readiness.isReady(player, "revision-2"));
    }

    @Test
    void playReadyMarksOnlyTheOfferedRevisionReady() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        UUID player = UUID.randomUUID();
        readiness.offered(player, "revision-1");

        assertTrue(readiness.markReady(player));
        assertTrue(readiness.isReady(player, "revision-1"));
        assertFalse(readiness.isAwaiting(player, "revision-1"));
        assertFalse(readiness.isReady(player, "revision-2"));
    }

    @Test
    void newerPackOfferInvalidatesPreviousReadyRevision() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        UUID player = UUID.randomUUID();
        readiness.offered(player, "revision-1");
        readiness.markReady(player);

        readiness.offered(player, "revision-2");

        assertFalse(readiness.isReady(player, "revision-2"));
        assertTrue(readiness.isAwaiting(player, "revision-2"));
        assertFalse(readiness.isReady(player, "revision-1"));
        assertTrue(readiness.markReady(player));
        assertTrue(readiness.isReady(player, "revision-2"));
    }

    @Test
    void stalePackOfferDoesNotBlockOrQualifyAsCurrent() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        UUID player = UUID.randomUUID();
        readiness.offered(player, "revision-1");
        readiness.markReady(player);

        assertFalse(readiness.isReady(player, "revision-2"));
        assertFalse(readiness.isAwaiting(player, "revision-2"));
    }

    @Test
    void disconnectClearsOfferedAndReadyState() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        UUID player = UUID.randomUUID();
        readiness.offered(player, "revision-1");
        readiness.markReady(player);

        readiness.remove(player);

        assertFalse(readiness.isReady(player, "revision-1"));
        assertFalse(readiness.isAwaiting(player, "revision-1"));
        assertFalse(readiness.markReady(player));
    }
}
