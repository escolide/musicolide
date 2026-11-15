package io.github.escolide.musicolide.util;

import java.util.Locale;
import java.util.Set;

/**
 * Pure policy for command roots the Discord adapter may execute.
 * Kept independent from Bukkit so it can be unit-tested without server APIs.
 */
public final class DiscordCommandPolicy {
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "help", "import", "import-url", "diagnostics", "download", "list", "search",
            "select", "page", "play", "confirm", "pause", "resume", "skip", "stop",
            "queue", "nowplaying", "progress", "volume", "speed", "mute", "unmute",
            "seek", "sync", "playlist", "alias", "priority", "reload");

    private DiscordCommandPolicy() {}

    public static boolean isAllowedRoot(String command) {
        return command != null && ALLOWED_ROOTS.contains(command.trim().toLowerCase(Locale.ROOT));
    }
}
