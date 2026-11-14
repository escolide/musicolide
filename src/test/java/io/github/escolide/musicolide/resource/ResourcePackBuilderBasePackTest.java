package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderBasePackTest {
    @TempDir
    Path temporary;

    @Test
    void buildsOneLibraryPackContainingAllTrackAssetsAndKeepsItsIdentityStable() throws Exception {
        ResourcePackBuilder builder = new ResourcePackBuilder(
                temporary, ResourcePackFormat.forMinecraftVersion("1.21.1"));

        Path audioA = temporary.resolve("track-a.ogg");
        Path audioB = temporary.resolve("track-b.ogg");
        Files.write(audioA, new byte[]{'O', 'g', 'g', 'S', 1, 2, 3});
        Files.write(audioB, new byte[]{'O', 'g', 'g', 'S', 4, 5, 6});
        builder.buildStreamed("ms_track_a", audioA);
        builder.buildStreamed("ms_track_b", audioB);

        ResourcePackBuilder.BasePackInfo first = builder.buildBasePack();
        ResourcePackBuilder.BasePackInfo second = builder.buildBasePack();

        assertEquals(first.id(), second.id());
        assertEquals(first.sha1(), second.sha1());
        assertSame(second, builder.currentBasePackInfo());
        assertTrue(Files.size(temporary.resolve("packs").resolve(first.fileName())) > 0);

        try (ZipFile zip = new ZipFile(temporary.resolve("packs").resolve(first.fileName()).toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertNotNull(zip.getEntry("assets/ms_track_a/sounds.json"));
            assertNotNull(zip.getEntry("assets/ms_track_a/sounds/music.ogg"));
            assertNotNull(zip.getEntry("assets/ms_track_b/sounds.json"));
            assertNotNull(zip.getEntry("assets/ms_track_b/sounds/music.ogg"));
            assertEquals(5, zip.size(), "The library should contain pack metadata and both tracks' assets.");
        }
    }

    @Test
    void rebuildingLibraryChangesItsHashWhenANewTrackIsAdded() throws Exception {
        ResourcePackBuilder builder = new ResourcePackBuilder(
                temporary, ResourcePackFormat.forMinecraftVersion("1.21.1"));
        ResourcePackBuilder.BasePackInfo empty = builder.buildBasePack();

        Path audio = temporary.resolve("new-track.ogg");
        Files.write(audio, new byte[]{'O', 'g', 'g', 'S', 7, 8, 9});
        builder.buildStreamed("ms_new_track", audio);
        ResourcePackBuilder.BasePackInfo populated = builder.buildBasePack();

        assertEquals(empty.id(), populated.id());
        assertNotEquals(empty.sha1(), populated.sha1());
        assertNotEquals(empty.fileName(), populated.fileName());
        assertTrue(Files.exists(temporary.resolve("packs").resolve(empty.fileName())),
                "The previous immutable revision must remain available for in-flight downloads.");
        assertEquals(populated, builder.currentBasePackInfo());

        builder.cleanupLibraryArchives(java.util.Set.of(populated.sha1()));
        assertFalse(Files.exists(temporary.resolve("packs").resolve(empty.fileName())));
        assertTrue(Files.exists(temporary.resolve("packs").resolve(populated.fileName())));
    }
}
