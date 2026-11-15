package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordCommandAllowlistTest {
    @Test
    void permitsOnlyMusicolideCommandRoots() {
        assertTrue(MusicCommand.isDiscordCommandAllowed("play"));
        assertTrue(MusicCommand.isDiscordCommandAllowed("playlist"));
        assertTrue(MusicCommand.isDiscordCommandAllowed("import"));
        assertTrue(MusicCommand.isDiscordCommandAllowed("reload"));
        assertTrue(MusicCommand.isDiscordCommandAllowed("MUSIC"));

        assertFalse(MusicCommand.isDiscordCommandAllowed("op"));
        assertFalse(MusicCommand.isDiscordCommandAllowed("ban"));
        assertFalse(MusicCommand.isDiscordCommandAllowed("kick"));
        assertFalse(MusicCommand.isDiscordCommandAllowed("stop-server"));
        assertFalse(MusicCommand.isDiscordCommandAllowed("link"));
        assertFalse(MusicCommand.isDiscordCommandAllowed("unlink"));
        assertFalse(MusicCommand.isDiscordCommandAllowed(null));
    }
}
