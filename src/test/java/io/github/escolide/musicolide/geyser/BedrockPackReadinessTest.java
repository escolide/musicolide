package io.github.escolide.musicolide.geyser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPackReadinessTest {
    @Test
    void offeredPackWaitsUntilGeyserReportsPlayReady() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();

        readiness.offered("xuid:1234", "revision-1");

        assertFalse(readiness.isReady("player-1", "revision-1"));
        assertFalse(readiness.isAwaiting("player-1", "revision-1"));
        assertTrue(readiness.markReady("xuid:1234", "player-1"));
        assertTrue(readiness.isReady("player-1", "revision-1"));
        assertFalse(readiness.isReady("player-1", "revision-2"));
    }

    @Test
    void newerPackOfferInvalidatesPreviousReadyRevision() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        readiness.offered("xuid:1234", "revision-1");
        assertTrue(readiness.markReady("xuid:1234", "player-1"));

        readiness.offered("xuid:1234", "revision-2");

        assertFalse(readiness.isReady("player-1", "revision-2"));
        assertFalse(readiness.isReady("player-1", "revision-1"));
        assertTrue(readiness.markReady("xuid:1234", "player-1"));
        assertTrue(readiness.isReady("player-1", "revision-2"));
    }

    @Test
    void stalePackOfferDoesNotQualifyAsCurrent() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        readiness.offered("xuid:1234", "revision-1");
        assertTrue(readiness.markReady("xuid:1234", "player-1"));

        assertFalse(readiness.isReady("player-1", "revision-2"));
        assertFalse(readiness.isAwaiting("player-1", "revision-2"));
    }

    @Test
    void differentBedrockIdentitiesDoNotShareReadiness() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        readiness.offered("xuid:111", "revision-1");
        readiness.offered("xuid:222", "revision-1");
        assertTrue(readiness.markReady("xuid:111", "player-1"));

        assertTrue(readiness.isReady("player-1", "revision-1"));
        assertFalse(readiness.isReady("player-2", "revision-1"));
        assertTrue(readiness.markReady("xuid:222", "player-2"));
        assertTrue(readiness.isReady("player-2", "revision-1"));
    }

    @Test
    void disconnectClearsOfferedAndReadyState() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        readiness.offered("xuid:1234", "revision-1");
        assertTrue(readiness.markReady("xuid:1234", "player-1"));

        readiness.remove("player-1", "xuid:1234");

        assertFalse(readiness.isReady("player-1", "revision-1"));
        assertFalse(readiness.markReady("xuid:1234", "player-1"));
    }

    @Test
    void usernameFallbackCanIdentifyOfflineAuthenticatedConnection() {
        BedrockPackReadiness readiness = new BedrockPackReadiness();
        readiness.offered("name:bedrockuser", "revision-1");

        assertTrue(readiness.markReady("name:bedrockuser", "player-1"));
        assertTrue(readiness.isReady("player-1", "revision-1"));
    }
}
