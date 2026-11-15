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

    /** True only for the command that always creates a fresh playlist session. */
    public static boolean requiresPlaylistPlayCooldown(String commandLine) {
        if (commandLine == null) return false;
        String normalized = commandLine.trim();
        if (normalized.isEmpty()) return false;
        String[] arguments = normalized.split("\\s+");
        int index = 0;
        String first = unquoteRoot(arguments[0]);
        if (first.startsWith("/")) first = first.substring(1);
        if (first.equalsIgnoreCase("music")) index++;
        return index + 1 < arguments.length
                && unquoteRoot(arguments[index]).equalsIgnoreCase("playlist")
                && unquoteRoot(arguments[index + 1]).equalsIgnoreCase("play");
    }

    private static String unquoteRoot(String token) {
        if (token == null || token.length() < 2) return token == null ? "" : token;
        char first = token.charAt(0);
        char last = token.charAt(token.length() - 1);
        if (first == '"' && last == '"') {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }
}
