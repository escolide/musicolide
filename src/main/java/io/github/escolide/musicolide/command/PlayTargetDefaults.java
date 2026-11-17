package io.github.escolide.musicolide.command;

/**
 * Defines the implicit audience for /play when a target selector is omitted.
 * A spatial zone naturally targets all players and lets the zone filter them;
 * a non-spatial request retains the historical sender-only default.
 */
public final class PlayTargetDefaults {
    private PlayTargetDefaults() {}

    public static boolean isExplicitCenterOption(String option) {
        return option != null && option.equalsIgnoreCase("--center");
    }

    public static String defaultSelector(boolean playerSender, boolean spatialOption) {
        if (!playerSender) {
            throw new IllegalArgumentException(
                    "From console, specify a target such as @a or a player name.");
        }
        return spatialOption ? "@a" : "@s";
    }
}
