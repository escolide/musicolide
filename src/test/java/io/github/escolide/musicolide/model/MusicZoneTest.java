package io.github.escolide.musicolide.model;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MusicZoneTest {
    private final UUID world = UUID.randomUUID();

    @Test
    void circleUsesHorizontalRadius() {
        MusicZone zone = MusicZone.circle(world, 0, 64, 0, 10, false, 0, false, 0);
        assertTrue(zone.containsBase(world, 10, 200, 0));
        assertFalse(zone.containsBase(UUID.randomUUID(), 0, 64, 0));
        assertFalse(zone.containsBase(world, 10.01, 64, 0));
    }

    @Test
    void squareUsesConfiguredDimensions() {
        MusicZone zone = MusicZone.square(world, 0, 64, 0, 20, 10, 0, false, 0, false, 0);
        assertTrue(zone.containsBase(world, 10, 64, 5));
        assertFalse(zone.containsBase(world, 10.1, 64, 0));
        assertFalse(zone.containsBase(world, 0, 64, 5.1));
    }

    @Test
    void rotatedSquareStillContainsItsCenterAndUsesDistanceFade() {
        MusicZone zone = MusicZone.square(world, 4, 64, 8, 20, 10, Math.PI / 4,
                false, 0, true, 5);
        assertTrue(zone.containsBase(world, 4, 64, 8));
        assertEquals(MusicZone.AudioMode.MONO, zone.audioMode(world, 18, 64, 8));
        assertEquals(MusicZone.AudioMode.NONE, zone.audioMode(world, 30, 64, 8));
    }

    @Test
    void circleFadeTransitionsFromStereoToMono() {
        MusicZone zone = MusicZone.circle(world, 0, 0, 0, 10, false, 0, true, 5);
        assertEquals(MusicZone.AudioMode.STEREO, zone.audioMode(world, 10, 0, 0));
        assertEquals(MusicZone.AudioMode.MONO, zone.audioMode(world, 12, 0, 0));
        assertEquals(MusicZone.AudioMode.NONE, zone.audioMode(world, 15.1, 0, 0));
    }

    @Test
    void squareFadeDistanceIsEuclideanAtCorner() {
        MusicZone zone = MusicZone.square(world, 0, 0, 0, 10, 10, 0, false, 0, true, 5);
        assertEquals(Math.sqrt(18), zone.distanceOutsideBase(8, 8), 0.0001);
    }
}
