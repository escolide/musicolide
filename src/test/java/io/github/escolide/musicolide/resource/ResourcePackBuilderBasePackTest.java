package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderBasePackTest {
    @TempDir
    Path temporary;

    @Test
    void createsTinyStableConsentPackWithoutSongAudio() throws Exception {
        ResourcePackBuilder builder = new ResourcePackBuilder(
                temporary, ResourcePackFormat.forMinecraftVersion("1.21.1"));

        ResourcePackBuilder.BasePackInfo first = builder.buildBasePack();
        ResourcePackBuilder.BasePackInfo second = builder.buildBasePack();

        assertEquals(first.id(), second.id());
        assertEquals(first.sha1(), second.sha1());
        assertTrue(Files.size(temporary.resolve("packs").resolve(first.fileName())) < 4096);

        try (ZipFile zip = new ZipFile(temporary.resolve("packs").resolve(first.fileName()).toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertEquals(1, zip.size(), "The initial consent pack should contain metadata only.");
        }
    }
}
