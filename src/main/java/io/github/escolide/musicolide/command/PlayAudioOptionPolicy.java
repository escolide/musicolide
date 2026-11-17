package io.github.escolide.musicolide.command;

/** Validation rules for command-scoped audio options. */
public final class PlayAudioOptionPolicy {
    private PlayAudioOptionPolicy() {}

    public static void requireMonoForFade(boolean fadeSpecified, String audioMode) {
        if (fadeSpecified && (audioMode == null || !audioMode.equalsIgnoreCase("mono"))) {
            throw new IllegalArgumentException(
                    "The fade option requires mode:mono. Add mode:mono to your /play command.");
        }
    }

    public static boolean parseLoop(String value) {
        if (value != null && value.trim().equalsIgnoreCase("true")) return true;
        if (value != null && value.trim().equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Use loop:true or loop:false.");
    }
}
