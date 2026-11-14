package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.model.MusicZone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSessionTest {
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
    void streamedTrackResumeRestartsItsClockAndDoesNotPretendToSeek() throws Exception {
        MusicTrack track = new MusicTrack("stream-demo", "Stream demo", "", "ms_stream_demo",
                "ms_stream_demo.zip", "0000000000000000000000000000000000000000",
                60000, 60000, 1, 1000);
        assertTrue(track.isStreamed());
        assertEquals("ms_stream_demo:music", track.soundKey(0));

        PlaybackSession session = new PlaybackSession(UUID.randomUUID(), "tester",
                MusicZone.global(), true, List.of(), List.of(track), 0);
        session.startClockIfNeeded();
        Thread.sleep(30);
        session.pause();
        assertTrue(session.elapsedMs() >= 10);
        Thread.sleep(30);
        session.resume();
        assertTrue(session.elapsedMs() < 30);
        assertThrows(IllegalArgumentException.class, () -> session.seek(15000));
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
}
