package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.model.MusicZone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSessionTest {
    @Test
    void discordBroadcastOverrideResetsWhenTheSessionAdvancesToANewTrack() {
        MusicTrack first = sampleTrack("discord-override-first");
        MusicTrack second = sampleTrack("discord-override-second");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first, second), 0);

        session.discordBroadcastOverride(false);
        assertEquals(Boolean.FALSE, session.discordBroadcastOverride());

        session.advance();
        assertEquals("discord-override-second", session.current().id());
        assertNull(session.discordBroadcastOverride(),
                "The next track must follow the configured server-wide Discord default.");
    }

    @Test
    void serverWidePromotionRestoresTheOriginalAudienceAfterTheCurrentTrack() {
        UUID owner = UUID.randomUUID();
        UUID listener = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        MusicZone originalZone = MusicZone.circle(UUID.randomUUID(), 10, 64, 20,
                30, false, 0, false, 0);
        MusicTrack first = sampleTrack("promotion-first");
        MusicTrack second = sampleTrack("promotion-second");
        PlaybackSession session = new PlaybackSession(owner, "tester", originalZone,
                false, List.of(listener), List.of(first, second), 0);

        assertTrue(session.targets(listener));
        assertFalse(session.targets(unrelated));
        assertTrue(session.promoteToServerWide());
        assertTrue(session.serverWidePromotion());
        assertTrue(session.isServerWideGlobalSession());
        assertTrue(session.targets(unrelated));

        session.advance();

        assertEquals("promotion-second", session.current().id());
        assertFalse(session.serverWidePromotion());
        assertFalse(session.isServerWideGlobalSession());
        assertTrue(session.targets(listener));
        assertFalse(session.targets(unrelated));
        assertTrue(session.zone().sameAs(originalZone));
    }

    @Test
    void speedComparisonRecognizesCurrentAndQueuedValues() {
        MusicTrack first = sampleTrack("speed-current");
        MusicTrack second = sampleTrack("speed-next");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first, second), 0);

        assertTrue(session.speedIsCurrentOrPending(1.0));
        assertFalse(session.speedIsCurrentOrPending(1.5));

        session.startClockIfNeeded();
        assertFalse(session.setSpeed(1.5), "A live speed change is staged for the next track.");
        assertTrue(session.speedIsCurrentOrPending(1.5));
        assertFalse(session.speedIsCurrentOrPending(1.0));
    }

    @Test
    void urlPlaylistStoresItsOwnDeliveryOverrideWithoutChangingOrdinarySessions() {
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "playlist",
                MusicZone.global(), true, List.of(), List.of(sampleTrack("playlist-delivery")), 0);

        assertFalse(session.isUrlPlaylist());
        session.configureUrlPlaylist(true);
        assertTrue(session.isUrlPlaylist());
        assertTrue(session.playlistPerTrackDelivery());

        PlaybackSession libraryPlaylist = new PlaybackSession(UUID.randomUUID(), "playlist-library",
                MusicZone.global(), true, List.of(), List.of(sampleTrack("playlist-library")), 0);
        libraryPlaylist.configureUrlPlaylist(false);
        assertTrue(libraryPlaylist.isUrlPlaylist());
        assertFalse(libraryPlaylist.playlistPerTrackDelivery());
    }

    @Test
    void playbackRequestMatchesOnlyTheSameAudienceAndZone() {
        UUID owner = UUID.randomUUID();
        UUID listener = UUID.randomUUID();
        MusicZone zone = MusicZone.global();
        PlaybackSession session = new PlaybackSession(owner, "tester",
                zone, false, List.of(listener), List.of(sampleTrack("queue-current")), 0);

        assertTrue(session.matchesPlaybackRequest(false, List.of(listener), MusicZone.global()));
        assertFalse(session.isServerWideGlobalSession(), "A session with a named listener is not server-wide.");
        assertFalse(session.matchesPlaybackRequest(true, List.of(), MusicZone.global()));
        assertFalse(session.matchesPlaybackRequest(false, List.of(UUID.randomUUID()), MusicZone.global()));
        assertFalse(session.matchesPlaybackRequest(false, List.of(listener),
                MusicZone.circle(UUID.randomUUID(), 0, 64, 0, 20, false, 0, false, 0)));
    }

    private MusicTrack sampleTrack(String id) {
        return new MusicTrack(id, id, "", "ms_" + id, id + ".zip",
                "0000000000000000000000000000000000000000", 60000, 60000, 1, 1000);
    }

    @Test
    void audioModeIsSessionScopedAndPartOfQueueIdentity() {
        UUID listener = UUID.randomUUID();
        MusicZone zone = MusicZone.global();
        MusicTrack streamed = new MusicTrack("mode-stream", "Mode stream", "", "ms_mode_stream",
                "mode-stream.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        PlaybackSession stereo = new PlaybackSession(UUID.randomUUID(), "tester",
                zone, false, List.of(listener), List.of(streamed), 0);
        PlaybackSession mono = new PlaybackSession(UUID.randomUUID(), "tester",
                zone, false, List.of(listener), List.of(streamed), 0, true);

        assertFalse(stereo.isMono(), "Existing constructor overloads must remain stereo by default.");
        assertTrue(mono.isMono());
        assertTrue(stereo.matchesPlaybackAudienceAndZone(false, List.of(listener), zone));
        assertTrue(mono.matchesPlaybackAudienceAndZone(false, List.of(listener), zone));
        assertTrue(stereo.matchesPlaybackRequest(false, List.of(listener), zone));
        assertFalse(stereo.matchesPlaybackRequest(false, List.of(listener), zone, true));
        assertTrue(mono.matchesPlaybackRequest(false, List.of(listener), zone, true));
        assertFalse(mono.matchesPlaybackRequest(false, List.of(listener), zone));
        assertEquals("ms_mode_stream:music", streamed.soundKey(0));
        assertEquals("ms_mode_stream:mono", streamed.soundKey(0, true));

        MusicTrack segmented = new MusicTrack("mode-segmented", "Mode segmented", "", "mc_mode_segmented",
                "mode-segmented.zip", "0000000000000000000000000000000000000000",
                10000, 1000, 10, 1000);
        assertEquals("mc_mode_segmented:s_00007", segmented.soundKey(7));
        assertEquals("mc_mode_segmented:m_00007", segmented.soundKey(7, true));

        MusicTrack fadeSegmentedMono = new MusicTrack("mode-fade-segmented", "Fade segmented", "",
                "ms_mode_fade_segmented", "mode-fade-segmented.zip",
                "0000000000000000000000000000000000000000", 60000, 500, 120, 2000);
        assertTrue(fadeSegmentedMono.isStreamed(), "The stereo and Discord assets remain streamed.");
        assertTrue(fadeSegmentedMono.isStreamed(false));
        assertFalse(fadeSegmentedMono.isStreamed(true),
                "Mono Minecraft playback must use short segments to refresh fade gain.");
        assertEquals(1, fadeSegmentedMono.segmentCount(false));
        assertEquals(120, fadeSegmentedMono.segmentCount(true));
        assertEquals("ms_mode_fade_segmented:music", fadeSegmentedMono.soundKey(7, false));
        assertEquals("ms_mode_fade_segmented:m_00007", fadeSegmentedMono.soundKey(7, true));

        PlaybackSession monoSegmentSession = new PlaybackSession(UUID.randomUUID(), "mono-segment-tester",
                zone, false, List.of(listener), List.of(fadeSegmentedMono), 0, true);
        monoSegmentSession.seek(2500);
        assertEquals(5, monoSegmentSession.segmentIndex(),
                "Mono seeking should select the matching short segment without regenerating the streamed tail.");
    }

    @Test
    void onlyAllPlayerGlobalSessionsQualifyAsServerWideDiscordPlayback() {
        MusicTrack track = sampleTrack("discord-scope");
        PlaybackSession globalAll = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);
        PlaybackSession globalNamed = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), false, List.of(UUID.randomUUID()), List.of(track), 0);
        PlaybackSession spatialAll = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.circle(UUID.randomUUID(), 0, 64, 0, 30, false, 0, false, 0),
                true, List.of(), List.of(track), 0);

        assertTrue(globalAll.isServerWideGlobalSession());
        assertFalse(globalNamed.isServerWideGlobalSession());
        assertFalse(spatialAll.isServerWideGlobalSession());
    }

    @Test
    void timelineGenerationChangesWhenTheSameTrackIsRestartedOrAdvanced() {
        MusicTrack first = new MusicTrack("timeline-first", "First", "", "mc_timeline_first",
                "first.zip", "0000000000000000000000000000000000000000", 10000, 1000, 10, 1000);
        MusicTrack second = new MusicTrack("timeline-second", "Second", "", "mc_timeline_second",
                "second.zip", "0000000000000000000000000000000000000000", 10000, 1000, 10, 1000);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first, second), 0);

        long initial = session.timelineGeneration();
        session.startClockIfNeeded();
        session.pause();
        session.resume();
        long resumed = session.timelineGeneration();
        assertTrue(resumed > initial);

        session.seek(2500);
        long sought = session.timelineGeneration();
        assertTrue(sought > resumed);

        session.advance();
        assertTrue(session.timelineGeneration() > sought);
        assertEquals("timeline-second", session.current().id());
    }

    @Test
    void sessionWaitsForFirstPackReadySignalAndSupportsSegmentSeeking() {
        MusicTrack track = new MusicTrack("demo", "Demo", "", "mc_demo", "demo.zip",
                "0000000000000000000000000000000000000000", 10000, 1000, 10, 1000);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);
        assertEquals(0, session.elapsedMs());
        session.startClockIfNeeded();
        session.seek(4500);
        assertEquals(4, session.segmentIndex());
    }

    @Test
    void segmentedResumePreservesAbsoluteTimestampUntilSharedClockStarts() {
        MusicTrack track = new MusicTrack("segmented-resume", "Segmented resume", "", "mc_segmented_resume",
                "segmented-resume.zip", "0000000000000000000000000000000000000000",
                10000, 250, 40, 1000);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);

        session.startClockIfNeeded();
        session.seek(4250);
        assertEquals(17, session.segmentIndex());
        assertFalse(session.clockStarted());

        session.startClockIfNeeded();
        assertEquals(17, session.segmentIndex());
        session.pause();
        long pausedAt = session.elapsedMs();
        session.resume();

        assertFalse(session.clockStarted());
        assertEquals(pausedAt, session.elapsedMs());
        assertEquals(17, session.segmentIndex());

        session.startClockIfNeeded();
        assertTrue(session.clockStarted());
        assertTrue(session.elapsedMs() >= pausedAt);
        assertEquals(17, session.segmentIndex());
    }

    @Test
    void continuousResumeResetsToZeroButWaitsForSharedStartBarrier() {
        MusicTrack track = new MusicTrack("continuous-resume", "Continuous resume", "", "ms_continuous_resume",
                "continuous-resume.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);
        session.startClockIfNeeded();
        session.pause();
        session.resume();

        assertFalse(session.clockStarted());
        assertEquals(0, session.elapsedMs());
        session.startClockIfNeeded();
        assertTrue(session.clockStarted());
        assertTrue(session.elapsedMs() >= 0);
    }

    @Test
    void streamedTrackResumeRestartsItsClockAndDoesNotPretendToSeek() throws Exception {
        MusicTrack track = new MusicTrack("stream-demo", "Stream demo", "", "ms_stream_demo",
                "ms_stream_demo.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        assertTrue(track.isStreamed());
        assertEquals("ms_stream_demo:music", track.soundKey(0));

        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);
        session.startClockIfNeeded();
        Thread.sleep(180);
        session.pause();
        assertTrue(session.elapsedMs() >= 100);
        Thread.sleep(50);
        session.resume();
        assertTrue(session.elapsedMs() < 100);
        assertThrows(IllegalArgumentException.class, () -> session.seek(15000));
    }

    @Test
    void regeneratedResumePreservesPositionAndStartsClockOnlyAfterPackIsReady() throws Exception {
        MusicTrack original = new MusicTrack("stream-source", "Stream source", "", "ms_stream_source",
                "ms_stream_source.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        MusicTrack remainder = new MusicTrack("resume-a1b2", "Stream source", "", "mr_a1b2",
                "mr_a1b2.zip", "0000000000000000000000000000000000000000",
                42000, 42000, 1, 700);

        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(original), 0);
        session.startClockIfNeeded();
        Thread.sleep(150);
        session.pause();
        long savedPosition = session.elapsedMs();

        long generation = session.beginResumePreparation();
        assertTrue(generation > 0);
        assertTrue(session.resumePreparing());
        assertTrue(session.completeResumePreparation(generation, remainder));
        assertFalse(session.paused());
        assertFalse(session.resumePreparing());
        assertEquals(savedPosition, session.elapsedMs(),
                "The session clock must remain at the saved timestamp while the new pack is loading.");

        session.startClockIfNeeded();
        assertTrue(session.elapsedMs() >= savedPosition);
        assertEquals("mr_a1b2:music", session.playbackTrack().soundKey(0));
        assertTrue(session.playbackTrack().isStreamed());
    }

    @Test
    void streamedSeekRegeneratesTailAndStartsFromRequestedPosition() {
        MusicTrack original = new MusicTrack("seek-source", "Seek source", "", "ms_seek_source",
                "ms_seek_source.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        MusicTrack remainder = new MusicTrack("resume-seek", "Seek source", "", "mr_seek",
                "mr_seek.zip", "0000000000000000000000000000000000000000",
                30000, 30000, 1, 700);

        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(original), 0);
        session.startClockIfNeeded();
        long generation = session.beginSeekPreparation(30000);
        assertTrue(generation > 0);
        assertTrue(session.paused());
        assertTrue(session.resumePreparing());
        assertTrue(session.completeResumePreparation(generation, remainder));
        assertFalse(session.paused());
        assertEquals(30000, session.elapsedMs());
        assertEquals("mr_seek:music", session.playbackTrack().soundKey(0));
    }

    @Test
    void streamedSeekFromPausedStateKeepsSessionPaused() {
        MusicTrack original = new MusicTrack("seek-paused", "Seek paused", "", "ms_seek_paused",
                "ms_seek_paused.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        MusicTrack remainder = new MusicTrack("resume-paused", "Seek paused", "", "mr_paused",
                "mr_paused.zip", "0000000000000000000000000000000000000000",
                20000, 20000, 1, 700);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(original), 0);
        session.startClockIfNeeded();
        session.pause();
        long generation = session.beginSeekPreparation(40000);
        assertTrue(session.completeResumePreparation(generation, remainder));
        assertTrue(session.paused());
        assertEquals(40000, session.elapsedMs());
    }

    @Test
    void pausingDuringResumePreparationInvalidatesTheGeneratedTail() {
        MusicTrack original = new MusicTrack("cancel-source", "Cancel source", "", "ms_cancel_source",
                "ms_cancel_source.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(original), 0);
        session.startClockIfNeeded();
        session.pause();
        long generation = session.beginResumePreparation();
        assertTrue(session.resumePreparing());

        session.pause();

        assertFalse(session.resumePreparing());
        assertFalse(session.completeResumePreparation(generation,
                new MusicTrack("resume-cancel", "Cancel source", "", "mr_cancel", "mr_cancel.zip",
                        "0000000000000000000000000000000000000000", 30000, 30000, 1, 700)));
        assertTrue(session.paused());
    }

    @Test
    void advancingInvalidatesAnOutstandingResumePreparation() {
        MusicTrack first = new MusicTrack("first", "First", "", "ms_first", "ms_first.zip",
                "0000000000000000000000000000000000000000", 60000, 60000, 1, 1000);
        MusicTrack next = new MusicTrack("next", "Next", "", "mc_next", "mc_next.zip",
                "0000000000000000000000000000000000000000", 10000, 1000, 10, 1000);
        MusicTrack remainder = new MusicTrack("resume-c3d4", "First", "", "mr_c3d4", "mr_c3d4.zip",
                "0000000000000000000000000000000000000000", 30000, 30000, 1, 700);

        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first, next), 0);
        session.startClockIfNeeded();
        session.pause();
        long generation = session.beginResumePreparation();
        session.advance();
        assertFalse(session.completeResumePreparation(generation, remainder));
        assertEquals("next", session.current().id());
        assertEquals("mc_next", session.playbackTrack().namespace());
    }

    @Test
    void pendingPlaylistKeepsAnEmptySessionAliveUntilTheNextImportArrives() {
        MusicTrack first = sampleTrack("playlist-pending-first");
        MusicTrack second = sampleTrack("playlist-pending-second");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first), 0);

        assertFalse(session.isEmpty());
        session.advance();
        assertNull(session.current());
        assertTrue(session.isEmpty());

        session.queuePending(true);
        assertFalse(session.isEmpty(), "An asynchronous playlist must not be removed while the next import is pending.");
        session.enqueue(second);

        assertEquals("playlist-pending-second", session.current().id());
        assertFalse(session.isEmpty());
        session.queuePending(false);
    }

    @Test
    void enqueueDoesNotReplaceTheCurrentTrackOrResetItsTimeline() throws Exception {
        MusicTrack current = sampleTrack("queue-current-track");
        MusicTrack queued = sampleTrack("queue-next-track");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(current), 0);
        session.startClockIfNeeded();
        Thread.sleep(30);
        long beforeQueue = session.elapsedMs();
        long generation = session.timelineGeneration();

        session.enqueue(queued);

        assertEquals("queue-current-track", session.current().id());
        assertEquals("queue-next-track", session.upcoming(1).get(0).id());
        assertEquals(1, session.queueSize());
        assertEquals(generation, session.timelineGeneration());
        assertTrue(session.elapsedMs() >= beforeQueue,
                "Adding a queued track must not reset the current playback clock.");
        assertTrue(session.advance());
        assertEquals("queue-next-track", session.current().id());
    }

    @Test
    void liveSpeedChangeIsStagedUntilTheNextTrackAndScalesItsClock() {
        MusicTrack first = sampleTrack("speed-first");
        MusicTrack second = sampleTrack("speed-second");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first, second), 0);

        assertTrue(session.setSpeed(0.75), "Speed can be set before playback starts.");
        assertEquals(0.75, session.speed(), 0.0001);

        session.startClockIfNeeded();
        long now = System.currentTimeMillis();
        assertFalse(session.setSpeed(2.0), "An already-playing sound cannot change pitch in place.");
        assertEquals(0.75, session.speed(), 0.0001);
        assertEquals(2.0, session.pendingSpeed(), 0.0001);
        assertEquals(750L, session.elapsedMsAt(now + 1000) - session.elapsedMsAt(now),
                "The current track keeps its original playback rate.");

        assertTrue(session.advance());
        assertEquals("speed-second", session.current().id());
        assertEquals(2.0, session.speed(), 0.0001);
        assertNull(session.pendingSpeed());

        session.startClockIfNeeded();
        long nextStart = System.currentTimeMillis();
        assertEquals(2000L,
                session.elapsedMsAt(nextStart + 1000) - session.elapsedMsAt(nextStart),
                "The next track's session clock must match the client sound pitch.");
    }

    @Test
    void speedCanBeChangedWhilePausedAndRejectsOutOfRangeValues() {
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(sampleTrack("speed-paused")), 0);
        assertTrue(session.setSpeed(0.5));
        session.startClockIfNeeded();
        session.pause();

        assertTrue(session.setSpeed(1.5));
        assertEquals(1.5, session.speed(), 0.0001);
        assertThrows(IllegalArgumentException.class, () -> session.setSpeed(0.49));
        assertThrows(IllegalArgumentException.class, () -> session.setSpeed(2.01));
        assertThrows(IllegalArgumentException.class, () -> session.setSpeed(Double.NaN));
    }

    @Test
    void repeatPlaylistRefillsTheQueue() {
        MusicTrack track = new MusicTrack("demo", "Demo", "", "mc_demo", "demo.zip",
                "0000000000000000000000000000000000000000", 10000, 1000, 10, 1000);
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);
        session.repeat(true);
        assertTrue(session.advance());
        assertEquals("demo", session.current().id());
    }
    @Test
    void overlapSuspensionFreezesTimelineUntilTheSessionWinsAgain() throws Exception {
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), false, List.of(UUID.randomUUID()),
                List.of(sampleTrack("overlap-suspended")), 0);
        session.startClockIfNeeded();
        Thread.sleep(80);
        long suspendedAt = session.elapsedMs();

        session.suspendClockForOverlap();
        assertFalse(session.clockStarted());
        Thread.sleep(80);
        assertEquals(suspendedAt, session.elapsedMs(),
                "A session hidden by another selected source must not consume its track or queue.");

        session.startClockIfNeeded();
        Thread.sleep(60);
        assertTrue(session.elapsedMs() > suspendedAt,
                "The session clock should resume when the player selects this session again.");
    }

    @Test
    void sameExactTargetAndZoneMatchForQueueingButDifferentPlayersDoNot() {
        UUID listener = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PlaybackSession personal = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), false, List.of(listener),
                List.of(sampleTrack("same-target")), 0);

        assertTrue(personal.matchesPlaybackAudienceAndZone(false, List.of(listener), MusicZone.global()));
        assertFalse(personal.matchesPlaybackAudienceAndZone(false, List.of(other), MusicZone.global()));
        assertFalse(personal.matchesPlaybackAudienceAndZone(true, List.of(), MusicZone.global()));
    }

    @Test
    void urlPlaylistImporterDoesNotAcceptStandardPlayQueueEntries() {
        UUID listener = UUID.randomUUID();
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), false, List.of(listener),
                List.of(sampleTrack("playlist-isolated")), 0);

        assertTrue(session.matchesStandardPlaybackQueue(false, List.of(listener), MusicZone.global()));
        session.configureUrlPlaylist(true);

        assertTrue(session.matchesPlaybackAudienceAndZone(false, List.of(listener), MusicZone.global()),
                "Audience identity remains the same; playlist isolation is a queue policy.");
        assertFalse(session.matchesStandardPlaybackQueue(false, List.of(listener), MusicZone.global()),
                "A standard /play request must not join the playlist importer's managed queue.");
    }

    @Test
    void overlapSuspensionMarkerIsConsumedOnceAndClearedOnAdvance() {
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), false, List.of(UUID.randomUUID()),
                List.of(sampleTrack("overlap-marker-first"), sampleTrack("overlap-marker-second")), 0);
        session.startClockIfNeeded();
        session.suspendClockForOverlap();

        assertTrue(session.consumeOverlapSuspended());
        assertFalse(session.consumeOverlapSuspended(),
                "The resumed session must not repeatedly force timeline catch-up.");

        session.startClockIfNeeded();
        session.advance();
        assertFalse(session.consumeOverlapSuspended(),
                "Advancing to the next track clears any previous overlap marker.");
    }


    @Test
    void loopQueuedAfterOrdinaryTracksIsTerminalAndRepeatsOnlyThatTrack() {
        MusicTrack first = sampleTrack("loop-queue-first");
        MusicTrack second = sampleTrack("loop-queue-second");
        MusicTrack loop = sampleTrack("loop-queue-terminal");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(first), 0);

        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(second, false));
        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(loop, true));
        assertTrue(session.hasLoopTerminal());
        assertFalse(session.isLoopActive());
        assertEquals(2, session.queueSize());
        assertEquals(PlaybackSession.QueueAddResult.LOOP_TERMINAL,
                session.addToQueue(sampleTrack("loop-queue-too-late"), false));

        assertTrue(session.advance());
        assertEquals(second.id(), session.current().id());
        assertFalse(session.isLoopActive());
        assertTrue(session.advance());
        assertEquals(loop.id(), session.current().id());
        assertTrue(session.isLoopActive());
        assertEquals(0, session.queueSize());

        long firstLoopTimeline = session.timelineGeneration();
        assertTrue(session.advance());
        assertEquals(loop.id(), session.current().id());
        assertTrue(session.timelineGeneration() > firstLoopTimeline);
        assertEquals(PlaybackSession.QueueAddResult.LOOP_TERMINAL,
                session.addToQueue(sampleTrack("loop-queue-still-locked"), false));
    }

    @Test
    void queuedLoopCanBeRemovedOrClearedButActiveLoopRequiresStopping() {
        MusicTrack current = sampleTrack("loop-edit-current");
        MusicTrack waiting = sampleTrack("loop-edit-waiting");
        MusicTrack loop = sampleTrack("loop-edit-terminal");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(current), 0);

        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(waiting, false));
        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(loop, true));
        assertTrue(session.removeQueuedTrack(loop.id()));
        assertFalse(session.hasLoopTerminal());
        assertEquals(1, session.queueSize());

        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(loop, true));
        assertEquals(2, session.clearQueuedTracks());
        assertFalse(session.hasLoopTerminal());
        assertEquals(0, session.queueSize());

        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(waiting, false));
        assertEquals(PlaybackSession.QueueAddResult.ADDED, session.addToQueue(loop, true));
        assertTrue(session.advance());
        assertEquals(waiting.id(), session.current().id());
        assertTrue(session.advance());
        assertEquals(loop.id(), session.current().id());
        assertTrue(session.isLoopActive());
        assertFalse(session.removeQueuedTrack(loop.id()));
        assertEquals(0, session.clearQueuedTracks());
        assertEquals(PlaybackSession.QueueAddResult.LOOP_TERMINAL,
                session.addToQueue(sampleTrack("loop-edit-after-active"), false));
    }

    @Test
    void aSingleTrackSessionCanLoopItsCurrentTrack() {
        MusicTrack loop = sampleTrack("loop-single-current");
        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(loop), 0);

        assertTrue(session.markCurrentAsLoop());
        assertTrue(session.isLoopActive());
        long initialTimeline = session.timelineGeneration();
        assertTrue(session.advance());
        assertEquals(loop.id(), session.current().id());
        assertTrue(session.timelineGeneration() > initialTimeline);
        assertFalse(session.markCurrentAsLoop());
    }
}
