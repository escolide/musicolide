package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicZone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackAudiencePolicyTest {
    @Test
    void onlyIdenticalAllPlayerAudiencesShareQueuesAcrossOwners() {
        UUID world = UUID.randomUUID();
        MusicZone global = MusicZone.global();
        MusicZone zone = MusicZone.circle(world, 0, 64, 0, 30, false, 0, false, 0);
        MusicZone otherZone = MusicZone.circle(world, 10, 64, 0, 30, false, 0, false, 0);

        assertTrue(PlaybackAudiencePolicy.sharesQueueAcrossOwners(true, true, global, global));
        assertTrue(PlaybackAudiencePolicy.sharesQueueAcrossOwners(true, true, zone, zone));
        assertFalse(PlaybackAudiencePolicy.sharesQueueAcrossOwners(false, false, global, global));
        assertFalse(PlaybackAudiencePolicy.sharesQueueAcrossOwners(true, true, zone, otherZone));
        assertFalse(PlaybackAudiencePolicy.sharesQueueAcrossOwners(true, false, global, global));
    }

    @Test
    void switchCycleIsPersonalThenZoneThenPublic() {
        UUID world = UUID.randomUUID();
        MusicZone personal = MusicZone.global();
        MusicZone zone = MusicZone.circle(world, 0, 64, 0, 30, false, 0, false, 0);

        assertEquals(0, PlaybackAudiencePolicy.switchCycleRank(false, personal)); // @s / @p
        assertEquals(1, PlaybackAudiencePolicy.switchCycleRank(false, zone));    // personal zone
        assertEquals(1, PlaybackAudiencePolicy.switchCycleRank(true, zone));     // zone audience
        assertEquals(2, PlaybackAudiencePolicy.switchCycleRank(true, personal)); // @a
    }

    @Test
    void forceIsLimitedToTheSingleExplicitListener() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(PlaybackAudiencePolicy.isPersonalTarget(false, List.of(player), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(true, List.of(), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(false, List.of(player, other), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(false, List.of(other), player));
    }
    @Test
    void manualChoiceWinsAndOtherwiseCurrentPlaybackStaysSelected() {
        UUID personal = UUID.randomUUID();
        UUID zone = UUID.randomUUID();
        UUID publicSession = UUID.randomUUID();
        List<UUID> eligible = List.of(personal, zone, publicSession);

        assertEquals(zone, PlaybackAudiencePolicy.preferredEligibleSession(
                zone, personal, eligible), "An explicit switch overrides the previous audible session.");
        assertEquals(personal, PlaybackAudiencePolicy.preferredEligibleSession(
                null, personal, eligible), "Newly overlapping sources must not replace current audio.");
        assertNull(PlaybackAudiencePolicy.preferredEligibleSession(
                null, UUID.randomUUID(), eligible), "A session that is no longer eligible cannot stay selected.");
    }

}
