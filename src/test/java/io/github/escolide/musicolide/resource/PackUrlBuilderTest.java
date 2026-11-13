package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackUrlBuilderTest {
    @Test
    void detectedMinecraftPortReplacesAStaleConfiguredPort() {
        assertEquals(
                "http://91.197.6.134:22912/packs/musicolide-base.zip",
                PackUrlBuilder.build("http://91.197.6.134:2688", "/packs",
                        "musicolide-base.zip", 22912, -1));
    }

    @Test
    void explicitExternalPortOverridesDetectedListenerPort() {
        assertEquals(
                "http://music.example.net:25580/music/packs/track.zip",
                PackUrlBuilder.build("http://music.example.net", "/music/packs",
                        "track.zip", 22912, 25580));
    }

    @Test
    void configuredPortIsPreservedWhenAutoDetectionIsDisabled() {
        assertEquals(
                "http://localhost:8765/packs/track.zip",
                PackUrlBuilder.build("http://localhost:8765", "/packs",
                        "track.zip", -1, -1));
    }

    @Test
    void rejectsUnsafeFileNamesAndNonHttpSharedPortUrls() {
        assertThrows(IllegalArgumentException.class, () ->
                PackUrlBuilder.build("http://localhost", "/packs", "../secret.zip", 25565, -1));
        assertThrows(IllegalArgumentException.class, () ->
                PackUrlBuilder.build("https://localhost", "/packs", "track.zip", 25565, -1));
    }
}
