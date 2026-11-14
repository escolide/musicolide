package io.github.escolide.musicolide.playback;

import io.github.escolide.musicolide.model.MusicTrack;
import io.github.escolide.musicolide.model.MusicZone;

import java.util.*;

public final class PlaybackSession {
    private final UUID id = UUID.randomUUID();
    private final UUID owner;
    private final String ownerName;
    private final long startedAt;
    private final MusicZone zone;
    private final Set<UUID> explicitListeners;
    private final boolean allPlayers;
    private final Deque<MusicTrack> queue = new ArrayDeque<>();
    private final List<MusicTrack> repeatSource;
    private MusicTrack current;
    private MusicTrack playbackTrack;
    private long trackStartedAt;
    private long elapsedBaseMs;
    private long pausedOffsetMs;
    private long pendingOffsetMs = -1L;
    private boolean resumeAfterPreparation;
    private boolean paused;
    private boolean resumePreparing;
    private long resumeGeneration;
    private boolean clockStarted;
    private boolean repeat;
    private int priority;

    public PlaybackSession(UUID owner, String ownerName, MusicZone zone, boolean allPlayers,
                           Collection<UUID> listeners, Collection<MusicTrack> tracks, int priority) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.zone = zone;
        this.allPlayers = allPlayers;
        this.explicitListeners = new HashSet<>(listeners);
        this.queue.addAll(tracks);
        this.repeatSource = List.copyOf(tracks);
        this.startedAt = System.currentTimeMillis();
        this.priority = priority;
        advance();
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public long startedAt() { return startedAt; }
    public MusicZone zone() { return zone; }
    public MusicTrack current() { return current; }
    /** Original catalogue track or the temporary OGG tail currently being played. */
    public synchronized MusicTrack playbackTrack() { return playbackTrack == null ? current : playbackTrack; }
    public synchronized boolean paused() { return paused; }
    public synchronized boolean resumePreparing() { return resumePreparing; }
    public int priority() { return priority; }
    public void priority(int priority) { this.priority = priority; }
    public void repeat(boolean repeat) { this.repeat = repeat; }
    public boolean isEmpty() { return current == null && queue.isEmpty(); }
    public int queueSize() { return queue.size(); }
    public List<MusicTrack> upcoming(int count) { return queue.stream().limit(Math.max(0, count)).toList(); }
    public boolean targets(UUID player) { return allPlayers || explicitListeners.contains(player); }
    public boolean isOwner(UUID player) { return owner != null && owner.equals(player); }

    public synchronized boolean referencesTrack(String trackId) {
        if (trackId == null) return false;
        if (current != null && current.id().equals(trackId)) return true;
        if (playbackTrack != null && playbackTrack.id().equals(trackId)) return true;
        if (queue.stream().anyMatch(track -> track.id().equals(trackId))) return true;
        return repeat && repeatSource.stream().anyMatch(track -> track.id().equals(trackId));
    }

    public synchronized void enqueue(MusicTrack track) { queue.addLast(track); }

    public synchronized boolean advance() {
        if (queue.isEmpty() && repeat && !repeatSource.isEmpty()) queue.addAll(repeatSource);
        resumeGeneration++;
        resumePreparing = false;
        current = queue.pollFirst();
        playbackTrack = current;
        paused = false;
        elapsedBaseMs = 0;
        pausedOffsetMs = 0;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
        clockStarted = false;
        trackStartedAt = 0;
        return current != null;
    }

    public synchronized void startClockIfNeeded() {
        if (!clockStarted && current != null && !paused && !resumePreparing) {
            trackStartedAt = System.currentTimeMillis();
            clockStarted = true;
        }
    }

    public synchronized void pause() {
        if (resumePreparing) {
            // A second pause cancels an outstanding resume/seek preparation.
            resumeGeneration++;
            resumePreparing = false;
            pendingOffsetMs = -1L;
            resumeAfterPreparation = false;
            paused = true;
            return;
        }
        if (!paused && current != null) {
            pausedOffsetMs = clockStarted ? elapsedMs() : elapsedBaseMs;
            paused = true;
            resumePreparing = false;
            resumeGeneration++;
        }
    }

    /** Existing behaviour for legacy segmented and continuous modes. */
    public synchronized void resume() {
        if (!paused || current == null) return;
        resumeGeneration++;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        playbackTrack = current;
        if (current.isStreamed()) {
            // Plain continuous mode cannot seek a client-side streamed decoder.
            elapsedBaseMs = 0;
            pausedOffsetMs = 0;
            trackStartedAt = System.currentTimeMillis();
        } else {
            elapsedBaseMs = 0;
            trackStartedAt = System.currentTimeMillis() - pausedOffsetMs;
        }
        clockStarted = true;
        paused = false;
    }

    /**
     * Starts asynchronous timestamp-based regeneration. The session stays paused
     * until FFmpeg and the transient resource pack are ready for the listener set.
     */
    public synchronized long beginResumePreparation() {
        if (!paused || current == null || !current.isStreamed() || resumePreparing) return -1L;
        resumePreparing = true;
        pendingOffsetMs = pausedOffsetMs;
        resumeAfterPreparation = true;
        return ++resumeGeneration;
    }

    /**
     * Starts an asynchronous seek for a streamed track. Playback is stopped
     * immediately; a seek issued while paused remains paused after preparation.
     */
    public synchronized long beginSeekPreparation(long targetMs) {
        if (current == null || !current.isStreamed() || resumePreparing) return -1L;
        boolean wasPlaying = !paused;
        if (wasPlaying) pausedOffsetMs = elapsedMs();
        paused = true;
        resumePreparing = true;
        pendingOffsetMs = Math.max(0L, Math.min(current.durationMs() - 1L, targetMs));
        resumeAfterPreparation = wasPlaying;
        return ++resumeGeneration;
    }

    public synchronized boolean completeResumePreparation(long generation, MusicTrack remainder) {
        if (!paused || !resumePreparing || generation != resumeGeneration || current == null
                || remainder == null) return false;
        playbackTrack = remainder;
        pausedOffsetMs = pendingOffsetMs >= 0 ? pendingOffsetMs : pausedOffsetMs;
        elapsedBaseMs = pausedOffsetMs;
        // Start the clock only when playFor sees that the new pack is loaded.
        clockStarted = false;
        trackStartedAt = 0;
        paused = !resumeAfterPreparation;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
        return true;
    }

    public synchronized boolean failResumePreparation(long generation) {
        if (generation != resumeGeneration) return false;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
        return true;
    }

    public synchronized void cancelResumePreparation() {
        resumeGeneration++;
        resumePreparing = false;
        pendingOffsetMs = -1L;
        resumeAfterPreparation = false;
    }

    public synchronized void seek(long millis) {
        if (current == null) return;
        if (current.isStreamed()) {
            throw new IllegalArgumentException(
                    "Seeking within continuous streamed tracks requires resumable playback mode.");
        }
        playbackTrack = current;
        elapsedBaseMs = 0;
        pausedOffsetMs = Math.max(0, Math.min(current.durationMs() - 1, millis));
        trackStartedAt = System.currentTimeMillis() - pausedOffsetMs;
        clockStarted = true;
    }

    public synchronized long elapsedMs() {
        if (paused) return pausedOffsetMs;
        if (!clockStarted) return elapsedBaseMs;
        return Math.max(0, elapsedBaseMs + System.currentTimeMillis() - trackStartedAt);
    }

    public synchronized int segmentIndex() {
        MusicTrack track = playbackTrack();
        return track == null ? -1 : (int) Math.min(track.segmentCount() - 1,
                Math.max(0, elapsedMs() - elapsedBaseMs) / Math.max(1, track.segmentMs()));
    }

    public synchronized boolean finished() {
        return current != null && clockStarted && !paused && !resumePreparing
                && elapsedMs() >= current.durationMs();
    }

    public synchronized void addListener(UUID uuid) { explicitListeners.add(uuid); }
    public synchronized void removeListener(UUID uuid) { explicitListeners.remove(uuid); }
}
