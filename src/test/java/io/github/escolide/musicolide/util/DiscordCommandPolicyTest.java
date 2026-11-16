package io.github.escolide.musicolide.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordCommandPolicyTest {
    @Test
    void recognizesPlaylistSessionCreationThroughDiscordCommandAliases() {
        assertTrue(DiscordCommandPolicy.requiresPlaylistPlayCooldown("playlist play mix"));
        assertTrue(DiscordCommandPolicy.requiresPlaylistPlayCooldown("/music playlist play mix"));
        assertTrue(DiscordCommandPolicy.requiresPlaylistPlayCooldown("music playlist play mix"));
        assertTrue(DiscordCommandPolicy.requiresPlaylistPlayCooldown("\"playlist\" \"play\" mix"));
        assertTrue(DiscordCommandPolicy.requiresPlaylistPlayCooldown("\"music\" playlist play mix"));
        assertTrue(DiscordCommandPolicy.requiresPlaylistPlayCooldown("\"/music\" \"playlist\" \"play\" mix"));

        assertFalse(DiscordCommandPolicy.requiresPlaylistPlayCooldown("play song-id"));
        assertFalse(DiscordCommandPolicy.requiresPlaylistPlayCooldown("playlist list"));
        assertFalse(DiscordCommandPolicy.requiresPlaylistPlayCooldown("pause"));
        assertFalse(DiscordCommandPolicy.requiresPlaylistPlayCooldown(null));
    }

    @Test
    void permitsOnlyMusicolideCommandRoots() {
        assertTrue(DiscordCommandPolicy.isAllowedRoot("play"));
        assertTrue(DiscordCommandPolicy.isAllowedRoot("playlist"));
        assertTrue(DiscordCommandPolicy.isAllowedRoot("import"));
        assertTrue(DiscordCommandPolicy.isAllowedRoot("reload"));
        assertTrue(DiscordCommandPolicy.isAllowedRoot("save"));
        assertTrue(DiscordCommandPolicy.isAllowedRoot("delete"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot("MUSIC"));

        assertFalse(DiscordCommandPolicy.isAllowedRoot("op"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot("ban"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot("kick"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot("stop-server"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot("link"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot("unlink"));
        assertFalse(DiscordCommandPolicy.isAllowedRoot(null));
    }
}
