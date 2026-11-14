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
    private long trackStartedAt;
    private long pausedOffsetMs;
    private boolean paused;
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
    public boolean paused() { return paused; }
    public int priority() { return priority; }
    public void priority(int priority) { this.priority = priority; }
    public void repeat(boolean repeat) { this.repeat = repeat; }
    public boolean isEmpty() { return current == null && queue.isEmpty(); }
    public int queueSize() { return queue.size(); }
    public List<MusicTrack> upcoming(int count) { return queue.stream().limit(Math.max(0, count)).toList(); }
    public boolean targets(UUID player) { return allPlayers || explicitListeners.contains(player); }
    public boolean isOwner(UUID player) { return owner != null && owner.equals(player); }

    /**
     * Returns true while this session may still need the track, including queued
     * tracks and the repeat source. Temporary-track cleanup uses this guard so a
     * URL import is never removed from an active or repeating session.
     */
    public synchronized boolean referencesTrack(String trackId) {
        if (trackId == null) return false;
        if (current != null && current.id().equals(trackId)) return true;
        if (queue.stream().anyMatch(track -> track.id().equals(trackId))) return true;
        return repeat && repeatSource.stream().anyMatch(track -> track.id().equals(trackId));
    }

    public synchronized void enqueue(MusicTrack track) { queue.addLast(track); }

    public synchronized boolean advance() {
        if (queue.isEmpty() && repeat && !repeatSource.isEmpty()) queue.addAll(repeatSource);
        current = queue.pollFirst();
        paused = false;
        pausedOffsetMs = 0;
        clockStarted = false;
        trackStartedAt = 0;
        return current != null;
    }

    public synchronized void startClockIfNeeded() {
        if (!clockStarted && current != null && !paused) {
            trackStartedAt = System.currentTimeMillis();
            clockStarted = true;
        }
    }

    public synchronized void pause() {
        if (!paused && current != null) {
            if (clockStarted) pausedOffsetMs = elapsedMs();
            paused = true;
        }
    }

    public synchronized void resume() {
        if (paused) {
            if (current != null && current.isStreamed()) {
                // A streamed Minecraft sound has no server API for seeking its
                // client-side decoder. Restart the stream and its clock together.
                pausedOffsetMs = 0;
                trackStartedAt = System.currentTimeMillis();
                clockStarted = true;
            } else if (clockStarted) {
                trackStartedAt = System.currentTimeMillis() - pausedOffsetMs;
            } else {
                trackStartedAt = System.currentTimeMillis();
                clockStarted = true;
            }
            paused = false;
        }
    }

    public synchronized void seek(long millis) {
        if (current == null) return;
        if (current.isStreamed()) {
            throw new IllegalArgumentException(
                    "Seeking within continuous streamed tracks is not supported by Minecraft's sound API.");
        }
        pausedOffsetMs = Math.max(0, Math.min(current.durationMs() - 1, millis));
        trackStartedAt = System.currentTimeMillis() - pausedOffsetMs;
        clockStarted = true;
    }

    public synchronized long elapsedMs() {
        if (!clockStarted) return 0;
        return paused ? pausedOffsetMs : Math.max(0, System.currentTimeMillis() - trackStartedAt);
    }

    public synchronized int segmentIndex() {
        return current == null ? -1 : (int) Math.min(current.segmentCount() - 1,
                elapsedMs() / Math.max(1, current.segmentMs()));
    }

    public synchronized boolean finished() {
        return current != null && clockStarted && !paused && elapsedMs() >= current.durationMs();
    }

    public synchronized void addListener(UUID uuid) { explicitListeners.add(uuid); }
    public synchronized void removeListener(UUID uuid) { explicitListeners.remove(uuid); }
}
