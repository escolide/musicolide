package io.github.escolide.musicolide.link;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordAccountLinkManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oneUseCodeCreatesPersistentOneToOneLink() throws Exception {
        Path database = temporaryDirectory.resolve("discord-links.db");
        UUID minecraft = UUID.randomUUID();

        try (DiscordAccountLinkManager manager =
                     new DiscordAccountLinkManager(database, Logger.getLogger("DiscordAccountLinkManagerTest"))) {
            String code = manager.createCode(minecraft, "PlayerOne");
            assertEquals(8, code.length());

            DiscordAccountLinkManager.Completion result = manager.completeLink("123456789012345678", code);
            assertEquals(DiscordAccountLinkManager.CompletionStatus.LINKED, result.status());
            assertEquals("PlayerOne", result.minecraftName());

            assertTrue(manager.findByDiscord("123456789012345678").isPresent());
            assertTrue(manager.findByMinecraft(minecraft).isPresent());
            assertEquals(DiscordAccountLinkManager.CompletionStatus.INVALID_CODE,
                    manager.completeLink("123456789012345678", code).status());
            assertThrows(IllegalStateException.class, () -> manager.createCode(minecraft, "PlayerOne"));
        }

        try (DiscordAccountLinkManager reopened =
                     new DiscordAccountLinkManager(database, Logger.getLogger("DiscordAccountLinkManagerTest"))) {
            assertTrue(reopened.findByDiscord("123456789012345678").isPresent());
            assertTrue(reopened.findByMinecraft(minecraft).isPresent());
        }
    }

    @Test
    void discordAndMinecraftAccountsCannotBeLinkedMoreThanOnce() throws Exception {
        try (DiscordAccountLinkManager manager = new DiscordAccountLinkManager(
                temporaryDirectory.resolve("discord-links.db"),
                Logger.getLogger("DiscordAccountLinkManagerTest"))) {
            UUID firstMinecraft = UUID.randomUUID();
            UUID secondMinecraft = UUID.randomUUID();

            String firstCode = manager.createCode(firstMinecraft, "First");
            assertEquals(DiscordAccountLinkManager.CompletionStatus.LINKED,
                    manager.completeLink("11111111111111111", firstCode).status());

            String secondCode = manager.createCode(secondMinecraft, "Second");
            assertEquals(DiscordAccountLinkManager.CompletionStatus.DISCORD_ALREADY_LINKED,
                    manager.completeLink("11111111111111111", secondCode).status());

            assertTrue(manager.unlinkMinecraft(firstMinecraft));
            String replacementCode = manager.createCode(secondMinecraft, "Second");
            assertEquals(DiscordAccountLinkManager.CompletionStatus.LINKED,
                    manager.completeLink("11111111111111111", replacementCode).status());
        }
    }

    @Test
    void aMinecraftAccountCannotBeRelinkedUntilItIsUnlinked() throws Exception {
        try (DiscordAccountLinkManager manager = new DiscordAccountLinkManager(
                temporaryDirectory.resolve("discord-links.db"),
                Logger.getLogger("DiscordAccountLinkManagerTest"))) {
            UUID minecraft = UUID.randomUUID();
            String code = manager.createCode(minecraft, "Player");
            manager.completeLink("22222222222222222", code);

            assertFalse(manager.unlinkMinecraft(UUID.randomUUID()));
            assertTrue(manager.unlinkMinecraft(minecraft));
            assertTrue(manager.createCode(minecraft, "Player").matches("[0-9]{8}"));
        }
    }
}
