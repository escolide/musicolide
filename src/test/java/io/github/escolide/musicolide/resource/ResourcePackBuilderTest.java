package io.github.escolide.musicolide.resource;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void reusesStartupLibrarySnapshotAndKeepsNewImportsIncrementalAcrossRestart() throws Exception {
        Path audio = tempDir.resolve("track.ogg");
        Files.write(audio, new byte[]{1, 2, 3, 4});

        ResourcePackFormat format = ResourcePackFormat.forMinecraftVersion("1.21.1-R0.1-SNAPSHOT");
        ResourcePackBuilder firstBuilder = new ResourcePackBuilder(tempDir, format);
        Path firstPack = firstBuilder.buildStreamed("ms_first", audio);
        MusicTrack firstTrack = track("first", "ms_first", firstPack);
        ResourcePackBuilder.BasePackInfo initial = firstBuilder.buildBasePack();

        assertTrue(firstBuilder.containsInBasePack(firstTrack));

        Path secondPack = firstBuilder.buildStreamed("ms_second", audio);
        MusicTrack secondTrack = track("second", "ms_second", secondPack);

        // A new builder instance simulates a server restart. It must reuse the
        // exact immutable startup snapshot rather than silently absorbing every
        // newly imported track and changing the cached library hash.
        ResourcePackBuilder restarted = new ResourcePackBuilder(tempDir, format);
        ResourcePackBuilder.BasePackInfo reused = restarted.loadOrBuildBasePack();

        assertEquals(initial.sha1(), reused.sha1());
        assertTrue(restarted.containsInBasePack(firstTrack));
        assertFalse(restarted.containsInBasePack(secondTrack));

        // A deliberate rebuild can publish a new snapshot containing both.
        ResourcePackBuilder.BasePackInfo refreshed = restarted.buildBasePack();
        assertTrue(restarted.containsInBasePack(firstTrack));
        assertTrue(restarted.containsInBasePack(secondTrack));
        assertNotEquals(initial.sha1(), refreshed.sha1());
    }

    private MusicTrack track(String id, String namespace, Path pack) throws Exception {
        return new MusicTrack(id, id, "", namespace, pack.getFileName().toString(),
                "0000000000000000000000000000000000000000", 1000, 1000, 1, Files.size(pack));
    }

    @Test
    void mapsEverySupportedReleaseFamilyToItsResourcePackFormat() {
        assertEquals("34", ResourcePackFormat.forMinecraftVersion("1.21.1-R0.1-SNAPSHOT").displayName());
        assertEquals("42", ResourcePackFormat.forMinecraftVersion("1.21.2-R0.1-SNAPSHOT").displayName());
        assertEquals("42", ResourcePackFormat.forMinecraftVersion("1.21.3-R0.1-SNAPSHOT").displayName());
        assertEquals("46", ResourcePackFormat.forMinecraftVersion("1.21.4-R0.1-SNAPSHOT").displayName());
        assertEquals("55", ResourcePackFormat.forMinecraftVersion("1.21.5-R0.1-SNAPSHOT").displayName());
        assertEquals("63", ResourcePackFormat.forMinecraftVersion("1.21.6-R0.1-SNAPSHOT").displayName());
        assertEquals("64", ResourcePackFormat.forMinecraftVersion("1.21.8-R0.1-SNAPSHOT").displayName());
        assertEquals("69.0", ResourcePackFormat.forMinecraftVersion("1.21.10-R0.1-SNAPSHOT").displayName());
        assertEquals("75.0", ResourcePackFormat.forMinecraftVersion("1.21.11-R0.1-SNAPSHOT").displayName());
        assertEquals("84.0", ResourcePackFormat.forMinecraftVersion("26.1.2.build.1-stable").displayName());
        assertEquals("88.0", ResourcePackFormat.forMinecraftVersion("26.2.build.1-stable").displayName());
        assertEquals("97.1", ResourcePackFormat.forMinecraftVersion("26.3.build.242-stable").displayName());
    }

    @Test
    void rejectsVersionsOutsideTheSupportedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourcePackFormat.forMinecraftVersion("1.20.6-R0.1-SNAPSHOT"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourcePackFormat.forMinecraftVersion("26.4.build.1-stable"));
    }

    @Test
    void writesLegacyMetadataForMinecraft1211() throws Exception {
        Path pack = buildPack("1.21.1-R0.1-SNAPSHOT");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            String json = readMetadata(zip);
            assertTrue(json.contains("\"pack_format\":34"), json);
            assertFalse(json.contains("\"min_format\""), json);
            assertSoundEntries(zip);
        }
    }

    @Test
    void writesModernMetadataForMinecraft263() throws Exception {
        Path pack = buildPack("26.3.build.242-stable");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            String json = readMetadata(zip);
            assertTrue(json.contains("\"min_format\":[97,1]"), json);
            assertTrue(json.contains("\"max_format\":97"), json);
            assertFalse(json.contains("\"pack_format\""), json);
            assertSoundEntries(zip);
        }
    }

    private Path buildPack(String serverVersion) throws Exception {
        Path track = tempDir.resolve("track-" + serverVersion.replaceAll("[^A-Za-z0-9]", "_"));
        Files.createDirectories(track.resolve("stereo"));
        Files.write(track.resolve("stereo/00000.ogg"), new byte[]{1, 2, 3});
        ResourcePackBuilder builder = new ResourcePackBuilder(tempDir,
                ResourcePackFormat.forMinecraftVersion(serverVersion));
        return builder.build("mc_demo", track, 1, 1000);
    }

    private String readMetadata(ZipFile zip) throws Exception {
        ZipEntry metadata = zip.getEntry("pack.mcmeta");
        assertNotNull(metadata);
        try (InputStream input = zip.getInputStream(metadata)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertSoundEntries(ZipFile zip) throws Exception {
        assertNotNull(zip.getEntry("assets/mc_demo/sounds.json"));
        assertNotNull(zip.getEntry("assets/mc_demo/sounds/mc_demo/stereo/00000.ogg"));
        assertNull(zip.getEntry("assets/mc_demo/sounds/mc_demo/mono/00000.ogg"));
        String sounds = readSoundDefinitions(zip);
        assertTrue(sounds.contains("\"s_00000\""), sounds);
        assertFalse(sounds.contains("\"m_00000\""), sounds);
    }

    private String readSoundDefinitions(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("assets/mc_demo/sounds.json");
        assertNotNull(entry);
        try (InputStream input = zip.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
