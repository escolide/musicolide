package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicZone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackAudiencePolicyTest {
    @Test
    void onlyIdenticalPublicAudiencesAndZonesShareQueuesAcrossOwners() {
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
    void switchCycleIsPersonalThenZoneOrTargetedThenPublic() {
        assertEquals(0, PlaybackAudiencePolicy.switchCycleRank(PlaybackAudiencePolicy.TargetScope.SELF));
        assertEquals(1, PlaybackAudiencePolicy.switchCycleRank(PlaybackAudiencePolicy.TargetScope.ZONE));
        assertEquals(1, PlaybackAudiencePolicy.switchCycleRank(PlaybackAudiencePolicy.TargetScope.TARGETED));
        assertEquals(2, PlaybackAudiencePolicy.switchCycleRank(PlaybackAudiencePolicy.TargetScope.PUBLIC));
    }

    @Test
    void targetScopeDistinguishesSelfTargetedZoneAndPublic() {
        assertEquals(PlaybackAudiencePolicy.TargetScope.SELF,
                PlaybackAudiencePolicy.resolveTargetScope("@s", false));
        assertEquals(PlaybackAudiencePolicy.TargetScope.TARGETED,
                PlaybackAudiencePolicy.resolveTargetScope("@p", false));
        assertEquals(PlaybackAudiencePolicy.TargetScope.TARGETED,
                PlaybackAudiencePolicy.resolveTargetScope("Alex", false));
        assertEquals(PlaybackAudiencePolicy.TargetScope.PUBLIC,
                PlaybackAudiencePolicy.resolveTargetScope("@a", false));
        assertEquals(PlaybackAudiencePolicy.TargetScope.ZONE,
                PlaybackAudiencePolicy.resolveTargetScope("@s", true));
        assertEquals(PlaybackAudiencePolicy.TargetScope.ZONE,
                PlaybackAudiencePolicy.resolveTargetScope("@a", true));
    }

    @Test
    void forceIsLimitedToTheExplicitSelfTarget() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(PlaybackAudiencePolicy.isPersonalTarget(
                PlaybackAudiencePolicy.TargetScope.SELF, false, List.of(player), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(
                PlaybackAudiencePolicy.TargetScope.PUBLIC, true, List.of(), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(
                PlaybackAudiencePolicy.TargetScope.TARGETED, false, List.of(player), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(
                PlaybackAudiencePolicy.TargetScope.SELF, false, List.of(player, other), player));
        assertFalse(PlaybackAudiencePolicy.isPersonalTarget(
                PlaybackAudiencePolicy.TargetScope.SELF, false, List.of(other), player));
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
