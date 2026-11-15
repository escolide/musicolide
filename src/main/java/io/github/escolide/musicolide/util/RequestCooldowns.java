package io.github.escolide.musicolide.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe, monotonic cooldown gate for expensive user-triggered operations.
 * A rejected request does not extend either cooldown.
 */
public final class RequestCooldowns {
    private static final long MAX_COOLDOWN_SECONDS = 86_400L;
    private static final long RETAIN_NANOS = MAX_COOLDOWN_SECONDS * 1_000_000_000L;

    private final Map<String, Long> actorRequests = new HashMap<>();
    private final Map<String, Long> globalRequests = new HashMap<>();

    /**
     * @return zero when admitted, otherwise the whole seconds remaining (rounded up)
     */
    public synchronized long tryAcquire(String action, String actor,
                                        long actorCooldownSeconds, long globalCooldownSeconds) {
        return tryAcquireAt(action, actor, actorCooldownSeconds, globalCooldownSeconds, System.nanoTime());
    }

    synchronized long tryAcquireAt(String action, String actor,
                                   long actorCooldownSeconds, long globalCooldownSeconds, long nowNanos) {
        String safeAction = action == null ? "default" : action;
        String safeActor = actor == null ? "unknown" : actor;
        long actorCooldown = toNanos(actorCooldownSeconds);
        long globalCooldown = toNanos(globalCooldownSeconds);
        prune(actorRequests, nowNanos);
        prune(globalRequests, nowNanos);

        long actorRemaining = remaining(actorRequests.get(safeAction + ":" + safeActor),
                actorCooldown, nowNanos);
        long globalRemaining = remaining(globalRequests.get(safeAction), globalCooldown, nowNanos);
        long remaining = Math.max(actorRemaining, globalRemaining);
        if (remaining > 0) return (remaining + 999_999_999L) / 1_000_000_000L;

        if (actorCooldown > 0) actorRequests.put(safeAction + ":" + safeActor, nowNanos);
        if (globalCooldown > 0) globalRequests.put(safeAction, nowNanos);
        return 0L;
    }

    private static long toNanos(long seconds) {
        if (seconds <= 0) return 0L;
        return Math.min(seconds, MAX_COOLDOWN_SECONDS) * 1_000_000_000L;
    }

    private static long remaining(Long lastRequest, long cooldown, long nowNanos) {
        if (lastRequest == null || cooldown <= 0) return 0L;
        long elapsed = nowNanos - lastRequest;
        return elapsed >= cooldown ? 0L : cooldown - Math.max(0L, elapsed);
    }

    private static void prune(Map<String, Long> requests, long nowNanos) {
        requests.entrySet().removeIf(entry -> nowNanos - entry.getValue() >= RETAIN_NANOS);
    }
}
